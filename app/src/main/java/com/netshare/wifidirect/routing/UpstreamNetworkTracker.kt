package com.netshare.wifidirect.routing

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.netshare.wifidirect.model.UpstreamNetworkInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/**
 * Tracks the active upstream internet network (Wi-Fi, Cellular, Ethernet)
 * so the Wi-Fi Direct Group Owner can route client traffic through the active interface.
 */
class UpstreamNetworkTracker(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _upstreamInfo = MutableStateFlow(UpstreamNetworkInfo())
    val upstreamInfo: StateFlow<UpstreamNetworkInfo> = _upstreamInfo.asStateFlow()

    @Volatile
    var activeNetwork: Network? = null
        private set

    private var isRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshNetworkState(network)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            refreshNetworkState(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            refreshNetworkState(network)
        }

        override fun onLost(network: Network) {
            if (activeNetwork == network) {
                refreshActiveDefaultNetwork()
            }
        }
    }

    fun startTracking() {
        if (isRegistered) return
        refreshActiveDefaultNetwork()
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true
        } catch (_: Exception) {
        }
    }

    fun stopTracking() {
        if (!isRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        isRegistered = false
    }

    fun refreshActiveDefaultNetwork() {
        val current = connectivityManager.activeNetwork
        if (current != null) {
            refreshNetworkState(current)
        } else {
            activeNetwork = null
            _upstreamInfo.value = UpstreamNetworkInfo()
        }
    }

    private fun refreshNetworkState(candidate: Network) {
        // Prefer the system's active default network if available
        val defaultNet = connectivityManager.activeNetwork ?: candidate
        val caps = connectivityManager.getNetworkCapabilities(defaultNet)
        val linkProps = connectivityManager.getLinkProperties(defaultNet)

        if (caps == null || linkProps == null) {
            activeNetwork = null
            _upstreamInfo.value = UpstreamNetworkInfo()
            return
        }

        val iface = linkProps.interfaceName ?: "unknown"
        // Ignore the P2P interface itself when looking for upstream internet
        if (iface.startsWith("p2p")) {
            return
        }

        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi ($iface)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data ($iface)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet ($iface)"
            else -> "Network ($iface)"
        }

        val ipv4 = linkProps.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress ?: "—"

        val dnsList = linkProps.dnsServers
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .ifEmpty { listOf("8.8.8.8", "1.1.1.1") }

        activeNetwork = defaultNet
        _upstreamInfo.value = UpstreamNetworkInfo(
            interfaceName = iface,
            transportType = transport,
            ipv4Address = ipv4,
            dnsServers = dnsList,
            isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        )
    }
}

