# NetShare Direct — Android Wi-Fi Direct Hotspot (No Proxy / No VPN)

**NetShare Direct** is a native Android application written in **Kotlin + Jetpack Compose** that creates a **Wi-Fi Direct Autonomous Group Owner (Hotspot)** and shares the host device's active internet connection (Wi-Fi Repeater mode or Mobile Data) over the `p2p-*` interface (`192.168.49.1/24`) **without requiring any HTTP/SOCKS Proxy or VPN client**.

---

## Key Features

1. **Wi-Fi Direct Autonomous Group Owner (Legacy AP Compatible)**
   - Uses Android's `WifiP2pManager` (`createGroup`) to broadcast a standard WPA2 Wi-Fi network (`DIRECT-NS-<CustomName>`).
   - Any device (Android, iOS, Windows, macOS, Linux, Smart TVs, consoles) can join it from its normal Wi-Fi list using the WPA2 password or the built-in **Wi-Fi QR Code** — no Wi-Fi Direct pairing required on client devices.
   - Supports custom SSID, custom WPA2 passphrase (8–63 characters), and frequency band selection (**Auto**, **2.4 GHz**, **5 GHz**) via `WifiP2pConfig.Builder` on Android 10+ (with automatic fallback for Android 8–9).

2. **Native Layer-3 Routing Engine (No Proxy / No VPN)**
   - **Auto Upstream Detection (`UpstreamNetworkTracker`)**: Automatically detects and tracks the active internet interface (`wlan0` for Wi-Fi-to-Wi-Fi repeating, `rmnet_data*` / `ccmni*` for Mobile Data, or `eth0` for Ethernet).
   - **Kernel `iptables` + Android Policy Routing (`NativeNatRouter`)**:
     - Enables IPv4 packet forwarding (`/proc/sys/net/ipv4/ip_forward`).
     - Configures `iptables -t nat POSTROUTING -s 192.168.49.0/24 -o <upstream> -j MASQUERADE`.
     - Configures `FORWARD` chain rules and `TCPMSS --clamp-mss-to-pmtu` so HTTPS/TLS traffic over cellular data never stalls on MTU mismatches.
     - Configures Android's multi-table policy routing (`ip rule` + `ip route` for `192.168.49.0/24` -> `<upstream_table>`) so `netd` fwmark policy routing forwards client packets transparently.
   - **System `ndc` Tethering Controller Mode**: Supports Android's native `ndc ipfwd` and `ndc nat enable` commands.
   - **Upstream Socket-Bound DNS Forwarder (`DnsForwarder`)**: Binds a UDP DNS resolver on `192.168.49.1` and forwards client DNS queries directly over the active upstream `android.net.Network` using `Network.bindSocket(DatagramSocket)`.

3. **Always-On Foreground Service & Live Telemetry**
   - Runs as a `connectedDevice` Foreground Service (`WifiDirectHotspotService`) holding a `PARTIAL_WAKE_LOCK` and `WIFI_MODE_FULL_LOW_LATENCY` lock so the hotspot stays active when the screen is off.
   - Real-time download/upload bandwidth monitor (`TrafficMonitor`) reading `/sys/class/net/<p2p_iface>/statistics` and `/proc/net/dev`.
   - Connected clients table resolving peer MAC addresses to `192.168.49.x` IPv4 addresses via `/proc/net/arp` and `ip neigh`.

---

## Project Structure

- [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml) — Permissions (`NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`, `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`) and service declarations.
- [`app/src/main/java/com/netshare/wifidirect/p2p/WifiDirectManager.kt`](app/src/main/java/com/netshare/wifidirect/p2p/WifiDirectManager.kt) — Wi-Fi P2P Autonomous Group Owner lifecycle, custom SSID/passphrase/band builder, and peer list management.
- [`app/src/main/java/com/netshare/wifidirect/routing/NativeNatRouter.kt`](app/src/main/java/com/netshare/wifidirect/routing/NativeNatRouter.kt) — Native `iptables` NAT masquerading, `ip rule` multi-table routing, `ndc` tethering, and ARP/neighbor IP resolution.
- [`app/src/main/java/com/netshare/wifidirect/routing/DnsForwarder.kt`](app/src/main/java/com/netshare/wifidirect/routing/DnsForwarder.kt) — Upstream `Network.bindSocket` UDP DNS forwarder on `192.168.49.1`.
- [`app/src/main/java/com/netshare/wifidirect/routing/UpstreamNetworkTracker.kt`](app/src/main/java/com/netshare/wifidirect/routing/UpstreamNetworkTracker.kt) — Tracks the active upstream internet interface (`wlan0`, `rmnet_data0`, `eth0`).
- [`app/src/main/java/com/netshare/wifidirect/service/WifiDirectHotspotService.kt`](app/src/main/java/com/netshare/wifidirect/service/WifiDirectHotspotService.kt) — Foreground service with WakeLock/WifiLock and live speed notification.
- [`app/src/main/java/com/netshare/wifidirect/ui/HotspotScreen.kt`](app/src/main/java/com/netshare/wifidirect/ui/HotspotScreen.kt) — Jetpack Compose Material 3 UI dashboard, QR code sheet, connected clients list, and live diagnostics console.

---

## Building & Running

1. Open `d:\Netshare` in **Android Studio** (Iguana / Jellyfish / Koala or newer).
2. Let Android Studio sync Gradle and download the Android SDK / Gradle 8.7 distribution.
3. Connect a physical Android device (Wi-Fi Direct requires real Wi-Fi hardware; emulators do not support `WifiP2pManager` Group Owner mode) and click **Run (`Shift+F10`)**, or build an APK via:
   ```bash
   ./gradlew assembleDebug
   ```
   The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

