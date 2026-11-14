package com.netshare.wifidirect.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.netshare.wifidirect.model.ConnectedClient
import com.netshare.wifidirect.model.HotspotBand
import com.netshare.wifidirect.model.HotspotConfig
import com.netshare.wifidirect.model.HotspotStatus
import com.netshare.wifidirect.model.HotspotUiState
import com.netshare.wifidirect.model.TrafficMetrics
import com.netshare.wifidirect.routing.NativeNatRouter
import com.netshare.wifidirect.routing.TrafficMonitor
import com.netshare.wifidirect.routing.UpstreamNetworkTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Coordinates the Android [WifiP2pManager] Autonomous Group Owner (Wi-Fi Direct Hotspot),
 * [UpstreamNetworkTracker], [NativeNatRouter] (with built-in DHCP & DNS servers), and [TrafficMonitor].
 */
@SuppressLint("MissingPermission")
class WifiDirectManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val upstreamTracker = UpstreamNetworkTracker(context)
    private val natRouter = NativeNatRouter(
        onLog = ::appendLog,
        onDhcpLeaseUpdated = { _ ->
            scope.launch { refreshGroupAndConnectionInfo() }
        }
    )
    private val trafficMonitor = TrafficMonitor()

    private val _state = MutableStateFlow(HotspotUiState())
    val state: StateFlow<HotspotUiState> = _state.asStateFlow()

    private var receiverRegistered = false
    private var monitorJob: Job? = null

    private val receiver = WifiDirectBroadcastReceiver(
        onP2pStateChanged = { enabled ->
            _state.update { it.copy(isWifiP2pEnabled = enabled) }
            if (!enabled) {
                appendLog("Wi-Fi Direct disabled by system; please enable Wi-Fi.")
            }
        },
        onConnectionChanged = {
            refreshGroupAndConnectionInfo()
        },
        onPeersChanged = {
            refreshGroupAndConnectionInfo()
        }
    )

    init {
        initializeChannel()
        upstreamTracker.startTracking()
        scope.launch {
            upstreamTracker.upstreamInfo.collect { upstream ->
                val prevUpstream = _state.value.upstreamInfo.interfaceName
                _state.update { it.copy(upstreamInfo = upstream) }
                if (_state.value.status == HotspotStatus.RUNNING &&
                    upstream.isConnected &&
                    upstream.interfaceName != prevUpstream &&
                    _state.value.p2pInterfaceName.isNotEmpty()
                ) {
                    appendLog("Upstream network changed to ${upstream.transportType}; updating NAT & DHCP route...")
                    applyNativeRouting(_state.value.p2pInterfaceName)
                }
            }
        }
    }

    private fun initializeChannel() {
        if (channel == null && wifiP2pManager != null) {
            channel = wifiP2pManager.initialize(context, Looper.getMainLooper()) {
                appendLog("Wi-Fi P2P channel disconnected; re-initializing...")
                channel = null
            }
        }
        registerBroadcastReceiver()
    }

    private fun registerBroadcastReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    fun updateConfig(newConfig: HotspotConfig) {
        _state.update { it.copy(config = newConfig, errorMessage = null) }
    }

    /**
     * Starts the Wi-Fi Direct Autonomous Group Owner (Hotspot), built-in DHCP server, and native routing.
     */
    fun startHotspot() {
        val manager = wifiP2pManager
        if (manager == null) {
            setError("This device does not support Wi-Fi Direct (WifiP2pManager unavailable).")
            return
        }
        initializeChannel()
        val ch = channel
        if (ch == null) {
            setError("Failed to initialize Wi-Fi P2P channel. Make sure Wi-Fi is turned ON.")
            return
        }

        val cfg = _state.value.config
        if (cfg.customPassphrase.length < 8 || cfg.customPassphrase.length > 63) {
            setError("WPA2 Passphrase must be between 8 and 63 characters.")
            return
        }

        _state.update {
            it.copy(
                status = HotspotStatus.STARTING,
                errorMessage = null
            )
        }
        appendLog("Starting Wi-Fi Direct Group Owner (${cfg.fullSsidPreview}, Band: ${cfg.band.label})...")

        setCustomP2pDeviceName(manager, ch, cfg.customSsidSuffix)

        manager.requestGroupInfo(ch) { existingGroup ->
            if (existingGroup != null) {
                manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        createP2pGroupInternal(manager, ch, cfg)
                    }

                    override fun onFailure(reason: Int) {
                        createP2pGroupInternal(manager, ch, cfg)
                    }
                })
            } else {
                createP2pGroupInternal(manager, ch, cfg)
            }
        }
    }

    private fun createP2pGroupInternal(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        cfg: HotspotConfig
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val bandConstant = when (cfg.band) {
                    HotspotBand.AUTO -> WifiP2pConfig.GROUP_OWNER_BAND_AUTO
                    HotspotBand.BAND_2GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                    HotspotBand.BAND_5GHZ -> WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                }

                val p2pConfig = WifiP2pConfig.Builder()
                    .setNetworkName(cfg.fullSsidPreview)
                    .setPassphrase(cfg.customPassphrase)
                    .enablePersistentMode(cfg.persistentGroup)
                    .setGroupOperatingBand(bandConstant)
                    .build()

                manager.createGroup(ch, p2pConfig, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        appendLog("Wi-Fi Direct Group Owner created with custom SSID & Passphrase.")
                        onGroupCreatedSuccessfully()
                    }

                    override fun onFailure(reason: Int) {
                        appendLog(
                            "Custom band/config createGroup returned ${reasonToString(reason)}; falling back to standard Group Owner..."
                        )
                        createFallbackStandardGroup(manager, ch)
                    }
                })
                return
            } catch (e: Exception) {
                appendLog("WifiP2pConfig.Builder error (${e.message}); falling back to default Group Owner...")
            }
        }

        createFallbackStandardGroup(manager, ch)
    }

    private fun createFallbackStandardGroup(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ) {
        manager.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                appendLog("Standard Wi-Fi Direct Group Owner created successfully.")
                onGroupCreatedSuccessfully()
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY) {
                    refreshGroupAndConnectionInfo()
                } else {
                    setError("Could not start Wi-Fi Direct Group Owner: ${reasonToString(reason)}. Ensure Wi-Fi and Location are ON.")
                }
            }
        })
    }

    private fun onGroupCreatedSuccessfully() {
        trafficMonitor.reset()
        startPeriodicMonitor()
        scope.launch {
            delay(350)
            refreshGroupAndConnectionInfo()
        }
    }

    /**
     * Stops the Wi-Fi Direct Group Owner and tears down all NAT/DHCP/routing rules.
     */
    fun stopHotspot() {
        val manager = wifiP2pManager
        val ch = channel
        _state.update { it.copy(status = HotspotStatus.STOPPING, errorMessage = null) }
        appendLog("Stopping Wi-Fi Direct Hotspot & cleaning up DHCP/NAT rules...")

        monitorJob?.cancel()
        monitorJob = null

        scope.launch {
            natRouter.disableRouting()

            if (manager != null && ch != null) {
                manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        appendLog("Wi-Fi Direct Group Owner stopped.")
                        resetToStoppedState()
                    }

                    override fun onFailure(reason: Int) {
                        appendLog("Group removed (code: ${reasonToString(reason)}).")
                        resetToStoppedState()
                    }
                })
            } else {
                resetToStoppedState()
            }
        }
    }

    private fun resetToStoppedState() {
        trafficMonitor.reset()
        _state.update {
            it.copy(
                status = HotspotStatus.STOPPED,
                activeSsid = "",
                activePassphrase = "",
                p2pInterfaceName = "",
                frequencyMhz = 0,
                connectedClients = emptyList(),
                routingActive = false,
                dhcpActive = false,
                activeDhcpSummary = "Standby (Pool 192.168.49.100–.240)",
                activeRoutingSummary = "Idle",
                traffic = TrafficMetrics()
            )
        }
    }

    fun refreshGroupAndConnectionInfo() {
        val manager = wifiP2pManager ?: return
        val ch = channel ?: return

        manager.requestConnectionInfo(ch) { connInfo ->
            val goIp = connInfo?.groupOwnerAddress?.hostAddress ?: "192.168.49.1"

            manager.requestGroupInfo(ch) { group ->
                if (group != null && group.isGroupOwner) {
                    handleActiveGroup(group, goIp)
                } else if (_state.value.status == HotspotStatus.RUNNING) {
                    appendLog("Wi-Fi Direct group was closed by system.")
                    scope.launch { natRouter.disableRouting() }
                    resetToStoppedState()
                }
            }
        }
    }

    private fun handleActiveGroup(group: WifiP2pGroup, groupOwnerIp: String) {
        val ssid = group.networkName ?: _state.value.config.fullSsidPreview
        val passphrase = group.passphrase ?: _state.value.config.customPassphrase
        val iface = group.`interface` ?: "p2p-wlan0-0"
        val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            group.frequency
        } else {
            0
        }

        val arpMap = natRouter.readArpTable()
        val dhcpLeases = natRouter.getDhcpLeases()
        val seenMacs = mutableSetOf<String>()

        val p2pClients = group.clientList.map { device ->
            val mac = (device.deviceAddress ?: "").lowercase()
            seenMacs.add(mac)
            val dhcpLease = dhcpLeases[mac]
            val ip = dhcpLease?.ipAddress ?: arpMap[mac] ?: findClosestArpIp(mac, arpMap)
            val friendlyName = device.deviceName.ifBlank {
                dhcpLease?.hostName ?: "Wi-Fi Client"
            }
            ConnectedClient(
                deviceName = friendlyName,
                macAddress = device.deviceAddress ?: "Unknown MAC",
                ipAddress = ip,
                primaryDeviceType = device.primaryDeviceType ?: "Standard Wi-Fi Peer",
                isGroupOwner = device.isGroupOwner,
                statusText = deviceStatusToString(device.status)
            )
        }.toMutableList()

        // Also include any legacy Wi-Fi clients discovered via DHCP lease that P2P clientList didn't list
        dhcpLeases.forEach { (mac, lease) ->
            val alreadyMatched = seenMacs.any { seen ->
                seen == mac || (seen.length >= 17 && mac.length >= 17 && seen.substring(3) == mac.substring(3))
            }
            if (!alreadyMatched) {
                p2pClients.add(
                    ConnectedClient(
                        deviceName = lease.hostName,
                        macAddress = mac,
                        ipAddress = lease.ipAddress,
                        primaryDeviceType = "DHCP Client",
                        isGroupOwner = false,
                        statusText = "Connected (DHCP)"
                    )
                )
            }
        }

        val wasRunning = _state.value.status == HotspotStatus.RUNNING
        val prevIface = _state.value.p2pInterfaceName

        _state.update {
            it.copy(
                status = HotspotStatus.RUNNING,
                activeSsid = ssid,
                activePassphrase = passphrase,
                p2pInterfaceName = iface,
                groupOwnerIp = groupOwnerIp,
                frequencyMhz = freq,
                connectedClients = p2pClients,
                errorMessage = null
            )
        }

        if (!wasRunning || prevIface != iface || !natRouter.isNatActive) {
            appendLog("Hotspot active -> SSID: $ssid | Interface: $iface | Gateway: $groupOwnerIp")
            applyNativeRouting(iface, groupOwnerIp)
        }
    }

    private fun applyNativeRouting(p2pInterface: String, groupOwnerIp: String = _state.value.groupOwnerIp) {
        scope.launch {
            upstreamTracker.refreshActiveDefaultNetwork()
            val upstream = _state.value.upstreamInfo
            val cfg = _state.value.config
            val primaryDns = upstream.dnsServers.firstOrNull() ?: cfg.upstreamDnsPrimary

            val result = natRouter.enableRouting(
                p2pInterface = p2pInterface,
                upstreamInterface = upstream.interfaceName,
                groupOwnerIp = groupOwnerIp,
                upstreamDns = primaryDns,
                mode = cfg.routingMode,
                enableDhcp = cfg.autoDhcpServer,
                speedLimit = cfg.speedLimit,
                upstreamNetworkProvider = { upstreamTracker.activeNetwork }
            )

            _state.update {
                it.copy(
                    routingActive = result.success,
                    activeRoutingSummary = result.summary,
                    dhcpActive = result.dhcpActive,
                    activeDhcpSummary = result.dhcpSummary
                )
            }
        }
    }

    private fun startPeriodicMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            var tick = 0
            while (isActive) {
                delay(1000L)
                val current = _state.value
                if (current.status == HotspotStatus.RUNNING) {
                    val metrics = trafficMonitor.sample(current.p2pInterfaceName)
                    _state.update { it.copy(traffic = metrics) }

                    tick++
                    if (tick % 3 == 0) {
                        refreshGroupAndConnectionInfo()
                    }
                }
            }
        }
    }

    private fun findClosestArpIp(peerMac: String, arpMap: Map<String, String>): String? {
        if (peerMac.length < 17 || arpMap.isEmpty()) return null
        val peerSuffix = peerMac.substring(3)
        return arpMap.entries.firstOrNull { (arpMac, _) ->
            arpMac.length >= 17 && arpMac.substring(3) == peerSuffix
        }?.value
    }

    private fun setCustomP2pDeviceName(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        name: String
    ) {
        try {
            val method = manager.javaClass.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(manager, ch, name.trim().ifEmpty { "NetShare" }, null)
        } catch (_: Exception) {
        }
    }

    private fun setError(message: String) {
        appendLog("ERROR: $message")
        _state.update {
            it.copy(
                status = HotspotStatus.ERROR,
                errorMessage = message
            )
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun appendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$time] $msg"
        _state.update { current ->
            val updated = (current.logs + entry).takeLast(120)
            current.copy(logs = updated)
        }
    }

    private fun reasonToString(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED (Wi-Fi Direct not supported)"
        WifiP2pManager.BUSY -> "BUSY (Wi-Fi framework is busy or Wi-Fi is off)"
        WifiP2pManager.ERROR -> "INTERNAL_ERROR"
        else -> "CODE_$reason"
    }

    private fun deviceStatusToString(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.INVITED -> "Authenticating"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Connected"
    }
}
