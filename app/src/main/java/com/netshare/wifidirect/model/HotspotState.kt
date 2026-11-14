package com.netshare.wifidirect.model

/**
 * Operating frequency band for the Wi-Fi Direct Group Owner.
 */
enum class HotspotBand(val label: String, val description: String) {
    AUTO("Auto (Recommended)", "Automatically selects 5 GHz or 2.4 GHz based on hardware"),
    BAND_2GHZ("2.4 GHz", "Maximum compatibility and longer range"),
    BAND_5GHZ("5 GHz", "High speed and low interference (requires 5 GHz support)")
}

/**
 * Optional bandwidth speed limit on the Wi-Fi Direct P2P interface.
 */
enum class SpeedLimitPreset(val label: String, val rateKbps: Int) {
    UNLIMITED("No Limit (Full Speed)", 0),
    LIMIT_2_MBPS("2 Mbps", 2_000),
    LIMIT_5_MBPS("5 Mbps", 5_000),
    LIMIT_10_MBPS("10 Mbps", 10_000),
    LIMIT_25_MBPS("25 Mbps", 25_000),
    LIMIT_50_MBPS("50 Mbps", 50_000)
}

/**
 * Native packet routing engine mode.
 */
enum class NatRoutingMode(val label: String, val description: String) {
    AUTO(
        "Auto Native Routing + DHCP",
        "Kernel NAT if rooted, or High-Speed Userspace Gateway (:8282) if unrooted"
    ),
    IPTABLES_NAT(
        "Kernel iptables + ip rule (Root)",
        "Full layer-3 NAT masquerading, FORWARD chain, DHCP & Android policy routing table"
    ),
    NDC_TETHER(
        "System ndc Tethering",
        "Android netd native tethering controller (ndc ipfwd + ndc nat + DHCP)"
    ),
    GATEWAY_DNS_ONLY(
        "Direct P2P + Built-in DHCP/DNS",
        "Starts Wi-Fi Direct Group Owner at 192.168.49.1 with Userspace Gateway (:8282)"
    )
}

/**
 * User-configurable settings for creating the Wi-Fi Direct Group Owner.
 */
data class HotspotConfig(
    val customSsidSuffix: String = "NetShare",
    val customPassphrase: String = "netshare123",
    val band: HotspotBand = HotspotBand.AUTO,
    val speedLimit: SpeedLimitPreset = SpeedLimitPreset.UNLIMITED,
    val autoDhcpServer: Boolean = true,
    val persistentGroup: Boolean = true,
    val routingMode: NatRoutingMode = NatRoutingMode.AUTO,
    val upstreamDnsPrimary: String = "8.8.8.8",
    val upstreamDnsSecondary: String = "1.1.1.1"
) {
    val fullSsidPreview: String
        get() {
            val cleaned = customSsidSuffix.trim().ifEmpty { "NetShare" }
            return if (cleaned.startsWith("DIRECT-", ignoreCase = true)) {
                cleaned
            } else {
                "DIRECT-NS-$cleaned"
            }
        }
}

/**
 * Information about a connected client on the Wi-Fi Direct P2P group.
 */
data class ConnectedClient(
    val deviceName: String,
    val macAddress: String,
    val ipAddress: String?,
    val primaryDeviceType: String,
    val isGroupOwner: Boolean = false,
    val statusText: String = "Connected"
)

/**
 * Information about the active upstream internet network (Wi-Fi, Cellular, or Ethernet).
 */
data class UpstreamNetworkInfo(
    val interfaceName: String = "Unknown",
    val transportType: String = "Disconnected",
    val ipv4Address: String = "—",
    val dnsServers: List<String> = emptyList(),
    val isConnected: Boolean = false
)

/**
 * Live bandwidth and byte counter metrics on the P2P interface.
 */
data class TrafficMetrics(
    val downloadSpeedBytesPerSec: Long = 0L,
    val uploadSpeedBytesPerSec: Long = 0L,
    val totalDownloadedBytes: Long = 0L,
    val totalUploadedBytes: Long = 0L
)

/**
 * Lifecycle status of the Wi-Fi Direct Hotspot.
 */
enum class HotspotStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/**
 * Complete UI & Service state for the Wi-Fi Direct Hotspot.
 */
data class HotspotUiState(
    val status: HotspotStatus = HotspotStatus.STOPPED,
    val isWifiP2pEnabled: Boolean = true,
    val config: HotspotConfig = HotspotConfig(),
    val activeSsid: String = "",
    val activePassphrase: String = "",
    val p2pInterfaceName: String = "",
    val groupOwnerIp: String = "192.168.49.1",
    val frequencyMhz: Int = 0,
    val connectedClients: List<ConnectedClient> = emptyList(),
    val upstreamInfo: UpstreamNetworkInfo = UpstreamNetworkInfo(),
    val routingActive: Boolean = false,
    val dhcpActive: Boolean = false,
    val activeDhcpSummary: String = "Standby (Pool 192.168.49.100–.240)",
    val activeRoutingSummary: String = "Idle",
    val traffic: TrafficMetrics = TrafficMetrics(),
    val errorMessage: String? = null,
    val logs: List<String> = emptyList()
)
