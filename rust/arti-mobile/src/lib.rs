use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU16, Ordering};
use std::sync::Arc;
use std::time::Duration;

use arti_client::config::BoolOrAuto;
use arti_client::{StreamPrefs, TorClient, TorClientConfigBuilder};
use futures::StreamExt;
use jni::objects::{JClass, JString};
use jni::sys::jint;
use jni::JNIEnv;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;
use tor_rtcompat::PreferredRuntime;

static BOOTSTRAP_PERCENT: AtomicI32 = AtomicI32::new(0);
static SOCKS_PORT: AtomicU16 = AtomicU16::new(0);
static IS_RUNNING: AtomicBool = AtomicBool::new(false);
static mut SHUTDOWN_TX: Option<oneshot::Sender<()>> = None;

#[cfg(target_os = "android")]
fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("GhostKey-Arti"),
    );
}

#[cfg(not(target_os = "android"))]
fn init_logging() {
    let _ = env_logger::try_init();
}

async fn handle_socks5_client(
    mut client: TcpStream,
    tor_client: Arc<TorClient<PreferredRuntime>>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
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

    let atyp = req_header[3];
    let target_host = match atyp {
        0x01 => {
            // IPv4
            let mut ipv4 = [0u8; 4];
            client.read_exact(&mut ipv4).await?;
            format!("{}.{}.{}.{}", ipv4[0], ipv4[1], ipv4[2], ipv4[3])
        }
        0x03 => {
            // Domain Name (e.g., onion address)
            let len = client.read_u8().await? as usize;
            let mut domain = vec![0u8; len];
            client.read_exact(&mut domain).await?;
            String::from_utf8(domain)?
        }
        0x04 => {
            // IPv6
            let mut ipv6 = [0u8; 16];
            client.read_exact(&mut ipv6).await?;
            return Err("IPv6 not supported".into());
        }
        _ => return Err("Invalid ATYP".into()),
    };

    let target_port = client.read_u16().await?;

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
    port_tx: oneshot::Sender<u16>,
    mut shutdown_rx: oneshot::Receiver<()>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let config = TorClientConfigBuilder::from_directories(
        PathBuf::from(state_dir),
        PathBuf::from(cache_dir),
    )
    .build()?;

    let tor_client: Arc<TorClient<PreferredRuntime>> = TorClient::builder()
        .config(config)
        .create_unbootstrapped()?;

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
        let _ = tor_clone.bootstrap().await;
        BOOTSTRAP_PERCENT.store(100, Ordering::Relaxed);
    });

    // Bind local SOCKS5 listener on 127.0.0.1:0 (ephemeral port assigned by OS)
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let local_addr = listener.local_addr()?;
    let port = local_addr.port();

    SOCKS_PORT.store(port, Ordering::Relaxed);
    IS_RUNNING.store(true, Ordering::Relaxed);
    let _ = port_tx.send(port);

    log::info!("Arti Tor SOCKS5 proxy bound to 127.0.0.1:{}", port);

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

    if IS_RUNNING.load(Ordering::Relaxed) {
        return SOCKS_PORT.load(Ordering::Relaxed) as jint;
    }

    let state_dir: String = match env.get_string(&j_state_dir) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let cache_dir: String = match env.get_string(&j_cache_dir) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };

    let (port_tx, port_rx) = oneshot::channel();
    let (shutdown_tx, shutdown_rx) = oneshot::channel();

    unsafe {
        SHUTDOWN_TX = Some(shutdown_tx);
    }

    std::thread::spawn(move || {
        let rt = match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
        {
            Ok(r) => r,
            Err(e) => {
                log::error!("Failed to create Tokio runtime for Arti: {}", e);
                return;
            }
        };

        rt.block_on(async move {
            if let Err(e) = run_arti_daemon(state_dir, cache_dir, port_tx, shutdown_rx).await {
                log::error!("Arti daemon error: {}", e);
            }
        });
    });

    // Wait up to 5 seconds for the SOCKS port to bind
    match port_rx.blocking_recv() {
        Ok(port) => port as jint,
        Err(_) => -1,
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
pub extern "system" fn Java_com_ghostkey_app_net_ArtiTorManager_stopNative(
    _env: JNIEnv,
    _class: JClass,
) {
    unsafe {
        if let Some(tx) = SHUTDOWN_TX.take() {
            let _ = tx.send(());
        }
    }
}
