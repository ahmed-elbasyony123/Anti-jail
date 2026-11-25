use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU16, Ordering};
use std::sync::Arc;

use arti_client::config::{BoolOrAuto, TorClientConfigBuilder};
use arti_client::{StreamPrefs, TorClient};
use futures::StreamExt;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tor_rtcompat::PreferredRuntime;

static BOOTSTRAP_PERCENT: AtomicI32 = AtomicI32::new(0);
static SOCKS_PORT: AtomicU16 = AtomicU16::new(0);
static IS_RUNNING: AtomicBool = AtomicBool::new(false);
static IS_STARTING: AtomicBool = AtomicBool::new(false);
static SHUTDOWN_TX: std::sync::Mutex<Option<oneshot::Sender<()>>> = std::sync::Mutex::new(None);
static LAST_ERROR: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

fn set_last_error(msg: String) {
    if let Ok(mut lock) = LAST_ERROR.lock() {
        *lock = msg;
    }
}

fn get_last_error() -> String {
    LAST_ERROR.lock().map(|l| l.clone()).unwrap_or_default()
}

#[cfg(target_os = "android")]
fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("GhostKey-Arti"),
    );
}

#[cfg(not(target_os = "android"))]
fn init_logging() {}

async fn read_socks5_target(
    client: &mut TcpStream,
) -> Result<(String, u16), Box<dyn std::error::Error + Send + Sync>> {
    // 1. SOCKS5 Handshake
    let mut ver_nmethods = [0u8; 2];
    client.read_exact(&mut ver_nmethods).await?;
    if ver_nmethods[0] != 0x05 {
        return Err("Not SOCKS5".into());
    }

    let nmethods = ver_nmethods[1] as usize;
    let mut methods = vec![0u8; nmethods];
    client.read_exact(&mut methods).await?;

    // Respond with Method 0x00 (No Authentication Required)
    client.write_all(&[0x05, 0x00]).await?;

    // 2. SOCKS5 Request
    let mut req_header = [0u8; 4];
    client.read_exact(&mut req_header).await?;
    if req_header[0] != 0x05 || req_header[1] != 0x01 {
        // Only CMD 0x01 (CONNECT) is supported
        client
            .write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
            .await?;
        return Err("Unsupported SOCKS command".into());
    }

    let target_host = match req_header[3] {
        0x01 => {
            let mut ipv4 = [0u8; 4];
            client.read_exact(&mut ipv4).await?;
            format!("{}.{}.{}.{}", ipv4[0], ipv4[1], ipv4[2], ipv4[3])
        }
        0x03 => {
            let len = client.read_u8().await? as usize;
            let mut domain = vec![0u8; len];
            client.read_exact(&mut domain).await?;
            String::from_utf8(domain)?
        }
        0x04 => {
            let mut ipv6 = [0u8; 16];
            client.read_exact(&mut ipv6).await?;
            return Err("IPv6 not supported".into());
        }
        _ => return Err("Invalid ATYP".into()),
    };

    let target_port = client.read_u16().await?;
    Ok((target_host, target_port))
}

async fn handle_socks5_client(
    mut client: TcpStream,
    tor_client: Arc<TorClient<PreferredRuntime>>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let (target_host, target_port) = tokio::time::timeout(
        std::time::Duration::from_secs(10),
        read_socks5_target(&mut client),
    )
    .await
    .map_err(|_| "SOCKS5 handshake timed out")??;

    // 3. Connect via Arti Tor Client with Onion Service Support
    let mut s_prefs = StreamPrefs::new();
    s_prefs.connect_to_onion_services(BoolOrAuto::Explicit(true));

    match tor_client
        .connect_with_prefs((target_host.as_str(), target_port), &s_prefs)
        .await
    {
        Ok(mut tor_stream) => {
            // Success response: BND.ADDR 127.0.0.1, Port 0
            client
                .write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0])
                .await?;

            // 4. Bidirectional Proxy
            let _ = tokio::io::copy_bidirectional(&mut client, &mut tor_stream).await;
            Ok(())
        }
        Err(e) => {
            log::error!("Arti Tor connect failed to {}:{}: {}", target_host, target_port, e);
            let _ = client
                .write_all(&[0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .await;
            Err(e.into())
        }
    }
}

async fn run_arti_daemon(
    state_dir: String,
    cache_dir: String,
    port_tx: std::sync::mpsc::SyncSender<Result<u16, String>>,
    mut shutdown_rx: oneshot::Receiver<()>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    fn fail_startup(
        msg: String,
        port_tx: &std::sync::mpsc::SyncSender<Result<u16, String>>,
    ) {
        log::error!("{}", msg);
        set_last_error(msg.clone());
        let _ = port_tx.send(Err(msg));
        IS_RUNNING.store(false, Ordering::Relaxed);
        IS_STARTING.store(false, Ordering::Relaxed);
    }

    // 1. Build TorClientConfig and disable fs-mistrust on Android directories
    let mut config_builder = TorClientConfigBuilder::from_directories(
        PathBuf::from(&state_dir),
        PathBuf::from(&cache_dir),
    );
    config_builder.storage().permissions().dangerously_trust_everyone();
    config_builder.address_filter().allow_onion_addrs(true);

    let config = match config_builder.build() {
        Ok(c) => c,
        Err(e) => {
            fail_startup(format!("Arti TorClientConfig build error: {}", e), &port_tx);
            return Err(e.into());
        }
    };

    // 2. Obtain runtime handle for Arti
    let runtime = match PreferredRuntime::current() {
        Ok(r) => r,
        Err(e) => {
            fail_startup(format!("Failed to acquire PreferredRuntime: {}", e), &port_tx);
            return Err(e.into());
        }
    };

    // 3. Create TorClient instance asynchronously using current runtime
    let tor_client: Arc<TorClient<PreferredRuntime>> = match TorClient::with_runtime(runtime)
        .config(config)
        .create_unbootstrapped_async()
        .await
    {
        Ok(tc) => tc,
        Err(e) => {
            fail_startup(format!("Arti TorClient create error: {}", e), &port_tx);
            return Err(e.into());
        }
    };

    // 4. Bind local SOCKS5 listener on 127.0.0.1:0 (ephemeral port assigned by OS)
    let listener = match TcpListener::bind("127.0.0.1:0").await {
        Ok(l) => l,
        Err(e) => {
            fail_startup(format!("Failed to bind SOCKS5 listener: {}", e), &port_tx);
            return Err(e.into());
        }
    };

    let local_addr = match listener.local_addr() {
        Ok(addr) => addr,
        Err(e) => {
            fail_startup(format!("Failed to get local address: {}", e), &port_tx);
            return Err(e.into());
        }
    };
    let port = local_addr.port();

    SOCKS_PORT.store(port, Ordering::Relaxed);
    IS_RUNNING.store(true, Ordering::Relaxed);
    IS_STARTING.store(false, Ordering::Relaxed);
    let _ = port_tx.send(Ok(port));

    log::info!("Arti Tor SOCKS5 proxy bound to 127.0.0.1:{}", port);

    // Track bootstrap events in the background
    let mut events = tor_client.bootstrap_events();
    tokio::spawn(async move {
        while let Some(status) = events.next().await {
            let pct = (status.as_frac() * 100.0) as i32;
            BOOTSTRAP_PERCENT.store(pct.clamp(0, 100), Ordering::Relaxed);
        }
    });

    // Start background bootstrap
    let tor_clone = tor_client.clone();
    tokio::spawn(async move {
        match tor_clone.bootstrap().await {
            Ok(_) => {
                log::info!("Arti Tor circuit bootstrapped successfully (100%)");
                BOOTSTRAP_PERCENT.store(100, Ordering::Relaxed);
            }
            Err(e) => {
                log::error!("Arti Tor bootstrap failed: {}", e);
                BOOTSTRAP_PERCENT.store(-1, Ordering::Relaxed);
            }
        }
    });

    loop {
        tokio::select! {
            _ = &mut shutdown_rx => {
                log::info!("Arti Tor shutting down on request.");
                break;
            }
            res = listener.accept() => {
                match res {
                    Ok((stream, _)) => {
                        let client = tor_client.clone();
                        tokio::spawn(async move {
                            let _ = handle_socks5_client(stream, client).await;
                        });
                    }
                    Err(e) => {
                        log::error!("SOCKS accept error: {}", e);
                    }
                }
            }
        }
    }

    IS_RUNNING.store(false, Ordering::Relaxed);
    IS_STARTING.store(false, Ordering::Relaxed);
    SOCKS_PORT.store(0, Ordering::Relaxed);
    BOOTSTRAP_PERCENT.store(0, Ordering::Relaxed);

    Ok(())
}

// ------------------------------------------------------------------ JNI Exports

#[no_mangle]
pub extern "system" fn Java_com_ghostkey_app_net_ArtiTorManager_startNative(
    mut env: JNIEnv,
    _class: JClass,
    j_state_dir: JString,
    j_cache_dir: JString,
) -> jint {
    init_logging();

    // Install ring crypto provider for rustls 0.23 and disable fs-mistrust for Android sandbox
    let _ = rustls::crypto::ring::default_provider().install_default();
    std::env::set_var("FS_MISTRUST_DISABLE_PERMISSIONS_CHECKS", "1");
    std::env::set_var("ARTI_FS_DISABLE_PERMISSION_CHECKS", "1");

    if IS_RUNNING.load(Ordering::Relaxed) {
        return SOCKS_PORT.load(Ordering::Relaxed) as jint;
    }

    // Guard against concurrent startNative calls
    if IS_STARTING.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_err() {
        // Another thread is currently starting Arti, wait briefly for port
        for _ in 0..50 {
            std::thread::sleep(std::time::Duration::from_millis(100));
            if IS_RUNNING.load(Ordering::Relaxed) {
                return SOCKS_PORT.load(Ordering::Relaxed) as jint;
            }
            if !IS_STARTING.load(Ordering::Relaxed) {
                break;
            }
        }
        if IS_RUNNING.load(Ordering::Relaxed) {
            return SOCKS_PORT.load(Ordering::Relaxed) as jint;
        }
        return -1;
    }

    let state_dir: String = match env.get_string(&j_state_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            let msg = format!("Invalid state_dir string: {}", e);
            set_last_error(msg);
            IS_STARTING.store(false, Ordering::Relaxed);
            return -1;
        }
    };
    let cache_dir: String = match env.get_string(&j_cache_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            let msg = format!("Invalid cache_dir string: {}", e);
            set_last_error(msg);
            IS_STARTING.store(false, Ordering::Relaxed);
            return -1;
        }
    };

    let (port_tx, port_rx) = std::sync::mpsc::sync_channel::<Result<u16, String>>(1);
    let (shutdown_tx, shutdown_rx) = oneshot::channel();

    if let Ok(mut lock) = SHUTDOWN_TX.lock() {
        *lock = Some(shutdown_tx);
    }

    std::thread::spawn(move || {
        let panic_res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let rt = match tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
            {
                Ok(r) => r,
                Err(e) => {
                    let msg = format!("Failed to create Tokio runtime: {}", e);
                    log::error!("{}", msg);
                    set_last_error(msg.clone());
                    let _ = port_tx.send(Err(msg));
                    IS_STARTING.store(false, Ordering::Relaxed);
                    return;
                }
            };

            rt.block_on(async move {
                if let Err(e) = run_arti_daemon(state_dir, cache_dir, port_tx, shutdown_rx).await {
                    log::error!("Arti daemon error: {}", e);
                    IS_STARTING.store(false, Ordering::Relaxed);
                    IS_RUNNING.store(false, Ordering::Relaxed);
                }
            });
        }));

        if let Err(panic_payload) = panic_res {
            let panic_msg = if let Some(s) = panic_payload.downcast_ref::<&str>() {
                format!("Arti thread panicked: {}", s)
            } else if let Some(s) = panic_payload.downcast_ref::<String>() {
                format!("Arti thread panicked: {}", s)
            } else {
                "Arti thread panicked with unknown error".to_string()
            };
            log::error!("{}", panic_msg);
            set_last_error(panic_msg);
            IS_STARTING.store(false, Ordering::Relaxed);
            IS_RUNNING.store(false, Ordering::Relaxed);
        }
    });

    // Wait up to 10 seconds with timeout for the SOCKS port to bind or error
    match port_rx.recv_timeout(std::time::Duration::from_secs(10)) {
        Ok(Ok(port)) => port as jint,
        Ok(Err(err_msg)) => {
            log::error!("Arti startup failed: {}", err_msg);
            set_last_error(err_msg);
            IS_STARTING.store(false, Ordering::Relaxed);
            -1
        }
        Err(_) => {
            if let Ok(mut lock) = SHUTDOWN_TX.lock() {
                if let Some(tx) = lock.take() {
                    let _ = tx.send(());
                }
            }
            let msg = get_last_error();
            if msg.is_empty() {
                set_last_error("Timed out waiting for Arti proxy initialization".to_string());
            }
            IS_STARTING.store(false, Ordering::Relaxed);
            IS_RUNNING.store(false, Ordering::Relaxed);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ghostkey_app_net_ArtiTorManager_getBootstrapProgressNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    BOOTSTRAP_PERCENT.load(Ordering::Relaxed)
}

#[no_mangle]
pub extern "system" fn Java_com_ghostkey_app_net_ArtiTorManager_getLastErrorNative(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let err = get_last_error();
    match env.new_string(err) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ghostkey_app_net_ArtiTorManager_stopNative(
    _env: JNIEnv,
    _class: JClass,
) {
    IS_STARTING.store(false, Ordering::Relaxed);
    IS_RUNNING.store(false, Ordering::Relaxed);
    SOCKS_PORT.store(0, Ordering::Relaxed);
    BOOTSTRAP_PERCENT.store(0, Ordering::Relaxed);
    if let Ok(mut lock) = SHUTDOWN_TX.lock() {
        if let Some(tx) = lock.take() {
            let _ = tx.send(());
        }
    }
}
