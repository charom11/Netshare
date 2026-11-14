# NetShare Direct — Android Wi-Fi Direct Hotspot

**NetShare Direct** is a native Android application built with **Kotlin + Jetpack Compose** that creates a **Wi-Fi Direct Autonomous Group Owner (Hotspot)** to share the host device's internet connection (Wi-Fi-to-Wi-Fi repeater or Mobile Data) across devices.

---

## Key Features

1. **Wi-Fi Direct Hotspot (Legacy AP Mode)**
   - Uses Android `WifiP2pManager` to broadcast a standard WPA2 network (`DIRECT-NS-<CustomName>`).
   - Clients (phones, laptops, tablets, Smart TVs) connect directly from their regular Wi-Fi list or by scanning the in-app **Wi-Fi QR Code**.
   - Custom SSID, WPA2 passphrase (8–63 chars), and band selection (**Auto**, **2.4 GHz**, **5 GHz**).

2. **Dual-Path Routing & Gateway Engine**
   - **Root Mode:** Full kernel `iptables` NAT masquerading, `ip rule` multi-table routing (priority 5000), TCP MSS clamping, and native packet forwarding.
   - **Non-Root Mode:** High-speed Userspace Gateway (`192.168.49.1:8282`) supporting HTTP CONNECT, full RFC 1928 SOCKS5, and PAC auto-configuration (`/pac`).
   - **Built-in RFC 2131 DHCP Server:** Automatically serves IP leases (`192.168.49.100–.240`), gateway `192.168.49.1`, and DNS.
   - **Captive Portal 204 Responder:** Responds to Google/Android `generate_204` checks with `204 No Content` to prevent Android's "No internet access" warning.

3. **In-App GitHub Update Checker & Automated CI/CD**
   - Built-in GitHub update checker dialog in the app: tap the download cloud icon in the top bar to check for new releases and download the latest APK in one tap.
   - GitHub Actions workflow (`.github/workflows/build.yml`) automatically builds the APK and creates GitHub Releases whenever you push or tag.

---

## Pushing to Your GitHub Repository

The local Git repository is already initialized with a clean initial commit. To push it to your GitHub account:

1. Create a new repository on [GitHub](https://github.com/new) (e.g., named `Netshare`).
2. Run these commands:
   ```bash
   git remote add origin https://github.com/<YOUR-GITHUB-USERNAME>/Netshare.git
   git branch -M main
   git push -u origin main
   ```

### Automated Updates via GitHub Actions:
* **On every push to `main`:** GitHub Actions will compile `NetShareDirect.apk` and attach it as a downloadable artifact in the **Actions** tab.
* **To create a release:** Create a git tag (e.g. `v1.0.1`):
  ```bash
  git tag v1.0.1
  git push origin v1.0.1
  ```
  GitHub Actions will automatically publish a new **GitHub Release** with the downloadable `NetShareDirect.apk` attached!
* **In the App:** Tap the cloud update icon at the top to check GitHub and download the update immediately.

---

## Getting Internet on the 2nd Phone (Client Phone)

Because non-rooted Android restricts raw layer-3 kernel packet forwarding:
1. Connect the 2nd phone to the hotspot Wi-Fi (`DIRECT-NS-NetShare`).
2. On the 2nd phone, install a free proxy-to-VPN tunnel app (like **Super Proxy** or **Every Proxy** from Google Play).
3. Set:
   - **Type:** `HTTP` or `SOCKS5`
   - **Server / Host:** `192.168.49.1`
   - **Port:** `8282`
4. Tap **Start** and allow the VPN permission prompt.
5. All apps, games, YouTube, WhatsApp, and browsers on the 2nd phone will now have full internet!

*(For PC or Mac: simply set Wi-Fi proxy to `192.168.49.1:8282` in Windows/macOS network settings).*

---

## Building Locally

```powershell
.\gradlew.bat assembleDebug
```
The output APK is at `app/build/outputs/apk/debug/app-debug.apk` and `NetShareDirect.apk`.
