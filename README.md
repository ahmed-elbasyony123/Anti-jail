# GHOSTKEY // Stealth E2EE Messaging Architecture

> **Full Privacy • Untraceable by ISP & Telecom Authorities • Powered by OpenKeychain & Tor Onion Routing**

---

## 🎯 Overview

**GhostKey** is a zero-trace, end-to-end encrypted messaging suite built specifically around **OpenKeychain (OpenPGP)** and **Tor Onion Routing**. It is engineered to defeat passive and active eavesdropping by Internet Service Providers (ISPs), state authorities, network wiretaps, and malicious relay hosts.

### Why Is It Untraceable?
1. **Zero ISP Visibility:** All network communication is routed through the **Tor Network** (via SOCKS5 proxy / Orbot) to a **Tor v3 Hidden Service (`.onion`)**. Your ISP only sees encrypted packets to a Tor Entry Guard node. They **cannot** see the destination, **cannot** resolve the `.onion` domain, and **cannot** read any content.
2. **Key Isolation via OpenKeychain:** Your private PGP keys **never** touch the messaging application code. All cryptographic operations (signing, encrypting, decrypting) are performed inside the sandboxed **OpenKeychain** app or your physical **YubiKey / NFC Hardware Token**.
3. **Zero-Knowledge Blind Dead-Drop:** The relay server does not have accounts, usernames, phone numbers, or metadata logs. It stores messages in **volatile RAM only** (zero disk storage).
4. **Burn-On-Read:** Once a message is retrieved or pushed via WebSocket, it is **instantly expunged** from RAM. Unread messages are wiped automatically after 1 hour.
5. **Anti-Forensics:** Enforces Android `FLAG_SECURE` (blocks screenshots, screen recording, and app switcher preview) with an **Emergency Panic Wipe** that zeroizes in-memory buffers immediately.

---

## 📂 Repository Structure

```text
├── server/                 # Tor Blind Relay Server (Node.js + WebSockets + RAM-only dead drop)
│   ├── server.js           # Ephemeral, zero-log in-memory relay
│   ├── torrc               # Tor v3 Onion Service configuration
│   └── package.json
│
├── android/                # Native Android Application (Kotlin + Jetpack Compose)
│   ├── app/src/main/java/com/ghostkey/app/
│   │   ├── MainActivity.kt # Stealth UI, FLAG_SECURE, Panic Button
│   │   ├── crypto/         # OpenKeychain AIDL & OpenPgpServiceConnection Bridge
│   │   ├── net/            # Tor SOCKS5 OkHttp Client & Live WebSocket
│   │   └── ui/             # Cyberpunk Dark Stealth Theme
│   ├── build.gradle.kts
│   └── AndroidManifest.xml # OpenKeychain package visibility declarations
│
├── web-client/             # Interactive Stealth Web Companion & Two-Party Simulator
│   ├── src/                # Vite + React + Tailwind + OpenPGP.js
│   └── package.json
│
├── docs/                   # Cryptographic Architecture & Threat Model
│   └── ARCHITECTURE.md
│
├── start-all.bat           # One-click launcher for Windows (Relay + Web Client)
└── start-all.ps1           # PowerShell launcher
```

---

## 🚀 Quick Start Guide

### Option 1: Run the Complete Suite (One-Click)
Run the automated launcher script from the root directory:
```powershell
.\start-all.bat
# or in PowerShell:
.\start-all.ps1
```
This starts:
* **Blind Relay:** `http://127.0.0.1:8765`
* **Web Simulator:** `http://localhost:5173`

---

### Option 2: Running the Tor Blind Relay Server
1. Navigate to the server folder:
   ```bash
   cd server
   npm install
   npm start
   ```
2. *(Optional - For complete Tor Anonymity)* Launch as a **Tor v3 Onion Service**:
   ```bash
   tor -f torrc
   ```
   Tor will generate your private `.onion` address in `./hidden_service/hostname`. Put this `.onion` address into the app!

---

### Option 3: Running the Interactive Web Companion
The web client lets you test the end-to-end cryptographic exchange immediately on your PC:
```bash
cd web-client
npm install
npm run dev
```
Open `http://localhost:5173`. You can switch between **Alice** and **Bob** to test real-time PGP encryption, blind relay dispatch, WebSocket delivery, and inspect raw PGP armor.

---

### Option 4: Building the Android Native App
1. Open the `android/` directory in **Android Studio**.
2. Install **OpenKeychain** on your Android device or emulator from F-Droid or Google Play:
   * Package: `org.sufficientlysecure.keychain`
3. Generate or import your PGP key in OpenKeychain.
4. *(Optional)* Install **Orbot: Tor for Android** and enable the SOCKS5 proxy on port `9050`.
5. Run the app on your device. The app will automatically connect to OpenKeychain and route all traffic through Tor.

---

## 🛡️ Security Features Checklist

| Security Feature | Implementation | Threat Defeated |
| :--- | :--- | :--- |
| **Transport Encryption & Anonymity** | Tor Onion Services (v3) + SOCKS5 | ISP Inspection, DPI, IP Logging, DNS Leakage |
| **End-to-End Cryptography** | OpenKeychain OpenPGP (Curve25519 / RSA) | Server Seizure, MITM, Payload Tampering |
| **Hardware Key Support** | YubiKey / Nitrokey via OpenKeychain NFC/USB | Private Key Theft, Malware on Android |
| **Relay Privacy** | Blind Mailbox Hash: `SHA-256(KeyID + Salt)` | Traffic Correlation, Sender/Receiver Profiling |
| **Ephemeral Storage** | Volatile RAM Queue + Burn-On-Read | Data At Rest Subpoenas, Server Forensics |
| **Client Protection** | Android `FLAG_SECURE` | Spyware Screen Recording, App Switcher Leaks |
| **Emergency Erase** | Panic Wipe Button | Physical Device Seizure |

---

## 📜 License
MIT Open Source. Designed for privacy researchers, journalists, and high-security communication.
