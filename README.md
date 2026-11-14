# NetShare Direct — Android Wi-Fi Direct Hotspot & Client

[![Build & Release NetShare Direct APK](https://github.com/charom11/Netshare/actions/workflows/build.yml/badge.svg)](https://github.com/charom11/Netshare/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/charom11/Netshare?color=emerald&label=Release)](https://github.com/charom11/Netshare/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**NetShare Direct** is a modern, full-featured Android application built with **Kotlin + Jetpack Compose** that lets you share your phone's internet connection (Wi-Fi-to-Wi-Fi repeater or Mobile Data) with other devices via **Wi-Fi Direct**, with **zero third-party apps required**.

NetShare Direct includes both **Host Mode** and **Client Mode** in a single, unified application:

* **Phone 1 (Host Mode):** Creates a high-speed Wi-Fi Direct hotspot with a built-in DHCP server, socket DNS forwarder, and userspace gateway (HTTP CONNECT & SOCKS5).
* **Phone 2 (Client Mode):** Connects to Phone 1 and activates an embedded, native C-powered VPN tunnel (`hev-socks5-tunnel` with `lwIP`) that routes all device apps, games, YouTube, WhatsApp, and browsers through Phone 1 with a single tap. **No Super Proxy or Every Proxy needed!**

---

## 📱 How It Works

```
┌────────────────────────────────────────────────────────┐
│                    Phone 1 (Host)                      │
│  [NetShare Direct: Host Mode]                          │
│  • Wi-Fi Direct Autonomous Group Owner (DIRECT-NS-...) │
│  • Built-in RFC 2131 DHCP Server (192.168.49.100-.240) │
│  • Userspace Gateway & SOCKS5 Server (192.168.49.1:8282│
│  • Active Internet: Mobile Data (4G/5G) or Wi-Fi       │
└───────────────────────────┬────────────────────────────┘
                            │ Wi-Fi Direct (WPA2)
                            ▼
┌────────────────────────────────────────────────────────┐
│                   Phone 2 (Client)                     │
│  [NetShare Direct: Client Mode]                        │
│  • Embedded Native C lwIP Tun2Socks Engine             │
│  • Captures all IP packets via Android VpnService      │
│  • Tunnels full TCP & UDP traffic to 192.168.49.1:8282 │
│  • ALL apps, games, and browsers have full internet!   │
└────────────────────────────────────────────────────────┘
```

---

## ✨ Key Features

### 1. Unified Dual-Mode App (Host & Client in One APK)
- Switch instantly between **Host (Phone 1)** and **Client (Phone 2)** using the top tab bar.
- Use the exact same APK on every device.

### 2. Wi-Fi Direct Hotspot (Host Mode)
- **Wi-Fi Repeater:** Share an existing Wi-Fi network without disconnecting from it (dual-interface Wi-Fi STA+AP).
- **Mobile Data Hotspot:** Share 4G/5G mobile data while bypassing restrictive carrier hotspot quotas.
- **Customizable SSID & Password:** Choose custom network names (`DIRECT-NS-...`) and WPA2 passphrases (8–63 chars).
- **Band Selection:** Auto, 2.4 GHz, or 5 GHz bands.
- **QR Code Quick Connect:** Display a scannable QR code for instant client pairing.

### 3. Built-in DHCP Server & Dual-Path Routing
- **RFC 2131 Pure Kotlin DHCP Server:** Automatically serves IP leases (`192.168.49.100`–`.240`), gateway `192.168.49.1`, and DNS servers.
- **Non-Root Userspace Gateway:** Bound to port `8282` with full support for:
  - HTTP CONNECT tunneling (HTTPS traffic).
  - RFC 1928 SOCKS5 proxy protocol (IPv4, IPv6, and domain names).
  - PAC auto-configuration script (`http://192.168.49.1:8282/pac`).
  - Captive portal `generate_204` responder to prevent "No internet access" popups.
- **Root Kernel NAT (Optional):** Automatic detection of root access to apply `iptables` MASQUERADE, `ip rule` priority 5000 multi-table routing, and TCP MSS clamping.

### 4. Native C Client Tunnel (Client Mode)
- Powered by the industry-standard native **`hev-socks5-tunnel`** engine using **`lwIP`**.
- Handles full TCP flow control, window scaling, selective ACK (SACK), out-of-order packet reassembly, and connection retransmissions.
- Embedded `mapdns` resolver: maps and caches domain names automatically so DNS lookups never fail or stall.
- Automatic routing loop prevention (`addDisallowedApplication`).
- Bundled native libraries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

### 5. In-App Direct Auto-Updater
- One-tap update checker: tap the cloud download icon in the top app bar.
- Automatically downloads and launches the Android APK installer directly inside the app using `DownloadManager` and `FileProvider`.

### 6. Production Signed Release Builds
- Pre-configured release keystore (RSA 2048-bit, SHA-256).
- Dual V2/V3 APK signature schemes applied by default to eliminate Google Play Protect warnings.

---

## 🚀 Quick Start Guide

### Step 1: Install NetShare Direct
Download and install [`NetShareDirect.apk`](https://github.com/charom11/Netshare/releases/latest) on **both Phone 1 and Phone 2**.

---

### Step 2: Start Hotspot on Phone 1 (Host)
1. Open **NetShare Direct** on Phone 1.
2. Select the **Host (Phone 1)** tab.
3. Tap **"Start Wi-Fi Direct Hotspot"** (grant Wi-Fi / Nearby Devices permissions when prompted).
4. The hotspot status will change to **HOTSPOT ACTIVE**, displaying the network name (e.g. `DIRECT-NS-NetShare`) and password.

---

### Step 3: Connect Phone 2 (Client)
1. On Phone 2, open Android **Wi-Fi Settings** and connect to Phone 1's Wi-Fi network (e.g. `DIRECT-NS-NetShare` using the password from Phone 1).
2. Open **NetShare Direct** on Phone 2 and select the **Client (Phone 2)** tab.
3. Tap **"Connect to NetShare Hotspot"**.
4. Accept Android's one-time VPN prompt (a key icon 🔑 will appear in the status bar).
5. **Done!** Open any browser, YouTube, WhatsApp, games, or social apps — all traffic is seamlessly routed through Phone 1 with full internet access!

---

### Connecting Other Devices (Laptops, PCs, Smart TVs)
Devices that don't run the NetShare app can connect using the built-in HTTP/SOCKS5 proxy:
1. Connect device to `DIRECT-NS-NetShare`.
2. In the device's Wi-Fi proxy settings, set:
   * **Proxy Host:** `192.168.49.1`
   * **Proxy Port:** `8282`
   * *(Or use Auto-Config PAC URL:* `http://192.168.49.1:8282/pac`*)*

---

## 🛠️ Building from Source

### Prerequisites
* Android SDK 34 (Android 14)
* OpenJDK 17 or higher
* Gradle 8.7+ (wrapper included)

### Compile Signed Release APK
```powershell
# Windows (PowerShell)
$env:JAVA_HOME = "C:\Path\To\jdk-17"
.\gradlew.bat assembleRelease
```

```bash
# Linux / macOS
export JAVA_HOME="/path/to/jdk-17"
./gradlew assembleRelease
```

The output signed APK will be generated at:
```
app/build/outputs/apk/release/app-release.apk
```

---

## 🔄 Automated CI/CD (GitHub Actions)

This repository includes a fully automated GitHub Actions workflow (`.github/workflows/build.yml`):
* **On push to `main`:** Automatically compiles the signed release APK and uploads it as a workflow artifact.
* **On tag push (`v*`):** Automatically creates an official **GitHub Release** and attaches `NetShareDirect.apk` for instant download and in-app updates:
  ```bash
  git tag v1.0.9
  git push origin v1.0.9
  ```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
Native tunneling engine powered by [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) under MIT License.
