# GHOSTKEY // E2EE Messaging Suite (v2.8.0)

> **End-to-End Encrypted • Embedded Tor (Arti Rust Engine) • Ed25519/X25519 • AES-256-GCM Groups • Blind Dead-Drop Relay • QR Pairing & LAN Mesh • Opus PTT Voice**

---

## 🎯 Overview

**GhostKey** is an end-to-end encrypted messaging suite designed to resist network eavesdropping, traffic inspection, and hostile relay infrastructure. It pairs standard OpenPGP cryptography (Ed25519 + X25519) with an **Embedded Tor Onion Routing Engine (Arti in Rust)** to minimize metadata exposure to ISPs and intermediate relays without requiring any external apps like Orbot.

> [!NOTE]
> **GhostKey v2.8.0 Updates:**
> - **Comprehensive Security Audit Remediation:** Enforced strict SEIPD/MDC integrity checks (anti-Efail), `SUBKEY_BINDING` and `KeyFlags.SIGN_DATA` verification, AES-only cipher enforcement, bounded LRU key caches, and Zip-Bomb recursion limits.
> - **Encrypted Group Chats & Disappearing Messages:** AES-256-GCM group encryption with AAD, cryptographic group invite validation, and automatic RAM-zeroing TTL message expiration.
> - **Hardened Transport & Relay:** Eliminated `.onion` DNS leaks, added CSRF/CSWSH protection, `SocksPort 0` + Tor v3 PoW defenses, PBKDF2-HMAC-SHA256 PIN/Duress PIN hashing, and ephemeral-ID Local LAN Mesh.
> - **Clean Modular Architecture:** Separated `MainActivity` into `MainViewModel`, `IdentityRepository`, `MessagingRepository`, and lean Jetpack Compose UI screens.

---

## 🛡️ Architecture & Threat Model

```mermaid
flowchart TD
    subgraph ClientA["Sender (Alice)"]
        UI_A["Stealth UI (FLAG_SECURE)"]
        CRYPTO_A["Embedded Crypto (Ed25519 / X25519)"]
        STORE_A["Android Keystore (AES-256-GCM)"]
        NET_A["Embedded Arti Tor Engine (Rust)\n(In-Process SOCKS5 & Circuits)"]
    end

    subgraph TorNet["Tor Anonymity Network"]
        GUARD["Guard Node"]
        RELAY["Relay Nodes"]
        ONION["Tor v3 Onion Service"]
    end

    subgraph BlindServer["Blind Relay Server (Node.js)"]
        MEM["RAM Queue (Per-Drop & Global Caps)"]
        AUTH["Token-Hash Authentication"]
        ACK["ACK-Based Deletion Handler"]
    end

    subgraph ClientB["Recipient (Bob)"]
        NET_B["Embedded Arti Tor Engine (Rust)\n(In-Process SOCKS5 & Circuits)"]
        CRYPTO_B["Embedded Crypto (Signature Verify)"]
        UI_B["Stealth UI (Verified Badge)"]
    end

    UI_A -->|1. Sign & Encrypt| CRYPTO_A
    CRYPTO_A -->|2. Dispatch via Embedded Tor| NET_A
    NET_A --> GUARD --> RELAY --> ONION
    ONION -->|3. POST /api/v2/send| MEM
    MEM -->|4. Push / Long-Poll| NET_B
    NET_B -->|5. Decrypt & Verify| CRYPTO_B
    CRYPTO_B -->|6. Plaintext + Sig Status| UI_B
    UI_B -->|7. POST /api/v2/ack| ACK
    ACK -->|8. Delete Confirmed IDs| MEM
```

### Core Security Properties

1. **Transport Protection via Embedded Tor Engine (Arti in Rust):**
   * Tor runs in-process inside the application using The Tor Project's official Rust implementation (**Arti**).
   * **Zero External Dependencies:** No need to install or run Orbot or third-party VPN apps; onion routing is 100% self-contained.
   * Local DNS leaks for `.onion` addresses are completely eliminated via in-memory stream routing.
   * ISPs only observe encrypted TLS connections to Tor Entry Guards, hiding destination `.onion` services.
2. **Modern Cryptographic Primitives:**
   * Embedded Bouncy Castle 1.78 engine (Android) and OpenPGP.js v6 (Web) — **no external apps required**.
   * Keys: **Ed25519** (sign/certify primary key) + **X25519** (encryption subkey).
   * **Sign-then-Encrypt:** Payloads are signed with the sender's private key before being wrapped in the recipient's encryption envelope. The relay cannot see who signed a message.
3. **Encrypted Push-to-Talk (PTT) Voice Notes (Opus):**
   * Encoded using native **Opus** (`.ogg`) at 32 kbps / 48 kHz for human voice clarity and lightweight payloads over Tor.
   * **Zero Disk Footprint:** Immediate temporary file shredding (zero-overwrite on disk); playback streams directly from volatile RAM (`MediaDataSource`) with zero disk writes.
4. **Blind Dead-Drop Relay (Protocol v2):**
   * Mailboxes use random 32-byte hex `dropId`s and secret 32-byte hex `readToken`s.
   * The server only stores `SHA-256(readToken)` in volatile RAM. Senders only need the `dropId`.
   * **ACK-Based Deletion:** Messages remain queued in memory until the recipient explicitly acknowledges receipt via `/api/v2/ack` or WebSocket ACK, preventing data loss on unstable circuits.
5. **Traffic Analysis Mitigation:**
   * Messages are padded using fixed bucket sizes (512 B for small messages, 16 KB for large messages) with cryptographically random filler to mask raw message lengths.
6. **Local Anti-Forensics & Storage Security:**
   * **Identity Persistence:** Private keys and peer configuration can be saved locally, encrypted with **AES-256-GCM** using keys backed by the **Android Keystore** (hardware TEE/StrongBox where available).
   * **Ephemeral Mode:** Toggle to run strictly in RAM without writing any identity data to disk.
   * **Panic Wipe & Burn Identity:** Destroys local Keystore aliases, purges audio buffers, deletes encrypted files, closes active sockets, and terminates memory.
   * **`FLAG_SECURE`:** Enforced on Android to block screenshots, video capture, and recent-app previews.

---

## 📂 Repository Structure

```text
├── server/                 # Blind Relay Server (Node.js + WebSockets)
│   ├── server.js           # Ephemeral, zero-log in-memory relay (v2 protocol)
│   ├── public/             # Static web companion (Opus PTT, zero CDNs, XSS-hardened)
│   ├── test/               # Node.js test suite (crypto, relay, web-e2e)
│   ├── torrc               # Tor v3 Onion Service configuration template
│   └── package.json
│
├── rust/                   # Embedded Tor Engine (Arti in Rust)
│   └── arti-mobile/        # In-process Tor client, SOCKS5 proxy & onion routing
│       ├── Cargo.toml      # arti-client (rustls + onion-service-client)
│       └── src/lib.rs      # JNI bridge for Android
│
├── android/                # Native Android Application (Kotlin + Jetpack Compose)
│   ├── app/src/main/java/com/ghostkey/app/
│   │   ├── MainActivity.kt          # Entry point, FLAG_SECURE, permissions & lock host
│   │   ├── MainViewModel.kt         # StateFlow UI state, Tor/LAN coordination & lifecycle
│   │   ├── audio/          # AudioRecorderManager (Opus), AudioPlayerManager (RAM-only)
│   │   ├── crypto/         # StandalonePgpEngine (LRU Cache), GroupCrypto, WireFormat, InPersonPairing
│   │   ├── data/           # ChatMessage, ContactAndGroup, SecureStore, IdentityRepository, MessagingRepository
│   │   ├── net/            # ArtiTorManager (Rust), TorRelayClient, LocalLanMesh, OrbotDiscovery
│   │   ├── notify/         # PushNotificationManager (Zero-leakage Tor alerts)
│   │   ├── service/        # TorForegroundService (Stealth sync service)
│   │   ├── util/           # GhostKeyUtils (Sanitization, formatting, hashing)
│   │   └── ui/             # Modular Jetpack Compose UI + lightweight AppIcons
│   │       ├── components/ # AudioBubble, MessageBubble
│   │       ├── dialogs/    # IntroduceContactDialog, KeyDialogs, PairingDialogs, SecurityStatusDialog
│   │       └── screens/    # MainModernApp, ChatsListScreen, CleanChatScreen, LockedScreen, SettingsScreen, TelegramDrawer, WelcomeOnboardingScreen
│   ├── build.gradle.kts
│   └── AndroidManifest.xml
│
├── docs/                   # Cryptographic Architecture & Threat Model
│   └── ARCHITECTURE.md
│
├── start-all.bat           # Windows launcher (Local Relay + Web Console)
└── start-all.ps1           # PowerShell launcher
```

---

## 🚀 Quick Start Guide

### 1. Running the Blind Relay Server & Web Console (Local or Tor)
```bash
cd server
npm install
npm test    # Runs 30 automated unit & integration tests
npm start   # Binds to 127.0.0.1:8765 by default (serves API + Web Console)
```
To run as a Tor Hidden Service:
```bash
tor -f torrc
# Hostname will be generated in ./hidden_service/hostname
```

### 2. Building the Android Application
```bash
cd android
./gradlew assembleRelease
# Output APK: android/app/build/outputs/apk/release/app-release.apk
```

---

## 📱 Mobile Step-by-Step Guide (دليل التشغيل الكامل على الهواتف)

### 🧩 المتطلبات:
1. **تطبيق GhostKey APK:** قم بتحميل أحدث إصدار من صفحة [GitHub Releases](https://github.com/ahmed-elbasyony123/Anti-jail/releases).
2. **لا يتطلب أي تطبيقات خارجية (Zero Config):** محرك **Tor Arti (Rust)** مدمج ذاتياً داخل التطبيق، ويقوم بإنشاء دوائر البصل والاتصال تلقائياً بدون الحاجة لتثبيت Orbot أو أي VPN (تطبيق Orbot أصبح خياراً احتياطياً فقط).
3. **محرك التشفير مدمج بالكامل (Bouncy Castle Ed25519/X25519):** التشفير والتوقيع يتم محلياً دون أي اعتماديات خارجية.

---

### 🛠️ خطوات الاستخدام:

```mermaid
flowchart LR
    A["1. افتح GhostKey\n(توليد مفاتيح Ed25519)"] --> B["2. شارك مفتاحك\n(My Contact QR أو نسخ المفتاح)"]
    B --> C["3. أضف صديقك\n(مسح QR أو لصق المفتاح)"]
    C --> D["4. تطابق كود الأمان\nSafety Code (30 رقماً)"]
    D --> E["5. محادثة مشفرة وموقعة\nوحذف بعد تأكيد الاستلام"]
```

#### الخطوة 1: تشغيل التطبيق ومشاركة المفتاح 🔑
* عند أول تشغيل، يولد التطبيق مفاتيح **Ed25519/X25519** ورقم صندوق عشوائي (`dropId`) ورمز قراءة سري (`readToken`).
* افتح القائمة الجانبية واضغط **Copy Full Public Key** أو أيقونة **My Contact QR** لمشاركة مفتاحك العام مع الطرف الآخر (المفتاح يحمل عنوان الـ Drop-ID تلقائياً).

#### الخطوة 2: إضافة الطرف الآخر 🤝
* من شاشة المحادثات اضغط زر المحادثة الجديدة واختر **In-Person QR Pairing (SAS)** لمسح الكود بالكاميرا مباشرة، أو **New Direct E2EE Chat** للصق مفتاح صديقك العام.

#### الخطوة 3: مطابقة كود الأمان (Safety Code) 🛡️
* افتح أيقونة الدرع داخل المحادثة أو **Safety Numbers & Verification** من الإعدادات وقارن الأرقام الـ 30 مع صديقك للتأكد من عدم وجود طرف ثالث (MITM).

#### الخطوة 4: التراسل الآمن 💬
* الرسائل والصوتيات والمرفقات مشفرة وموقعة، مع دعم المحادثات الجماعية المشفرة والرسائل المختفية (Disappearing Messages) وشبكة الـ LAN المحلية.
* يتم حذف الرسائل من ذاكرة السيرفر المؤقتة بمجرد وصول تأكيد استلام (ACK) من جهاز المستلم.

#### زر الطوارئ (Emergency Panic Wipe) 🔥
* ضغطة واحدة على **PANIC WIPE** في القائمة الجانبية (أو إدخال رمز الإكراه **Duress PIN** عند القفل) تمسح المفاتيح المخزنة ومفاتيح Android Keystore وبيانات Arti والرسائل من الذاكرة وتغلق التطبيق فوراً.

---

## 🔍 Security Audit Status & Known Limitations

| Component | Status | Details |
| :--- | :--- | :--- |
| **Transport Anonymity** | ✅ Implemented | Tor v3 Onion Routing with remote SOCKS5 DNS resolution. |
| **Cryptography** | ✅ Hardened | Ed25519 + X25519 (OpenPGP v4), signed-then-encrypted payloads. |
| **Relay Security** | ✅ Hardened | Random drop IDs, hashed read-tokens, ACK deletion, strict DoS limits, traversal-safe static file serving. |
| **Local Storage** | ✅ Hardened | AndroidKeyStore AES-256-GCM encrypted persistence; optional Ephemeral mode. |
| **Forward Secrecy** | ⚠️ Partial | Messages are encrypted using OpenPGP asymmetric subkeys (no per-message Double Ratchet). |
| **Relay Metadata** | ⚠️ Notice | Relay operators cannot read content, but can observe packet timing, payload sizes, and drop activity. |
| **Deployment Testing** | ⚠️ In Progress | Automated test suite passes (30/30). Live physical multi-device Tor testing is ongoing. |

---

## 📜 License
MIT License.
