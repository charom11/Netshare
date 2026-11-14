package com.netshare.wifidirect.routing

import android.net.Network
import com.netshare.wifidirect.model.NatRoutingMode
import com.netshare.wifidirect.model.SpeedLimitPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Native Layer-3 IP Forwarding, NAT Router, Built-in DHCP Server, Userspace Gateway (`:8282`),
 * and Bandwidth Shaper for the Wi-Fi Direct Group Owner (`p2p-*`, subnet `192.168.49.0/24`).
 */
class NativeNatRouter(
    private val onLog: (String) -> Unit,
    private val onDhcpLeaseUpdated: (DhcpServerEngine.DhcpLease) -> Unit = {}
) {
    private val dnsForwarder = DnsForwarder(onLog)
    private val dhcpServer = DhcpServerEngine(onLog, onDhcpLeaseUpdated)
    private val userspaceGateway = UserspaceGatewayServer(onLog)

    @Volatile
    var activeP2pInterface: String? = null
        private set

    @Volatile
    var activeUpstreamInterface: String? = null
        private set

    @Volatile
    var isNatActive: Boolean = false
        private set

    @Volatile
    var isDhcpActive: Boolean = false
        private set

    data class RoutingResult(
        val success: Boolean,
        val summary: String,
        val dhcpActive: Boolean,
        val dhcpSummary: String
    )

    fun getDhcpLeases(): Map<String, DhcpServerEngine.DhcpLease> = dhcpServer.getActiveLeases()

    /**
     * Enables the built-in DHCP server, DNS forwarder, Userspace Gateway (`192.168.49.1:8282`),
     * optional speed limiter, and native packet forwarding/NAT between [p2pInterface] and [upstreamInterface].
     */
    suspend fun enableRouting(
        p2pInterface: String,
        upstreamInterface: String,
        groupOwnerIp: String = "192.168.49.1",
        upstreamDns: String = "8.8.8.8",
        mode: NatRoutingMode = NatRoutingMode.AUTO,
        enableDhcp: Boolean = true,
        speedLimit: SpeedLimitPreset = SpeedLimitPreset.UNLIMITED,
        upstreamNetworkProvider: () -> Network?
    ): RoutingResult = withContext(Dispatchers.IO) {
        val cleanP2p = p2pInterface.trim().ifEmpty { "p2p-wlan0-0" }
        val cleanUpstream = upstreamInterface.trim().ifEmpty { "wlan0" }

        activeP2pInterface = cleanP2p
        activeUpstreamInterface = cleanUpstream

        // 1. Start the local socket-bound DNS forwarder on 192.168.49.1
        val dnsPort = dnsForwarder.start(
            groupOwnerIp = groupOwnerIp,
            upstreamDnsServer = upstreamDns,
            upstreamNetworkProvider = upstreamNetworkProvider
        )

        // 2. Start the Userspace Gateway Server on 192.168.49.1:8282 for non-root devices
        userspaceGateway.start(
            groupOwnerIp = groupOwnerIp,
            port = UserspaceGatewayServer.GATEWAY_PORT,
            upstreamNetworkProvider = upstreamNetworkProvider
        )

        // 3. Start the built-in RFC 2131 DHCP Server (hands out 192.168.49.100-.240 + Gateway + DNS)
        var dhcpPort = -1
        if (enableDhcp) {
            dhcpPort = dhcpServer.start(
                groupOwnerIp = groupOwnerIp,
                p2pInterface = cleanP2p,
                primaryDns = upstreamDns
            )
            isDhcpActive = dhcpPort > 0
        } else {
            dhcpServer.stop()
            isDhcpActive = false
        }

        val dhcpStatusText = if (isDhcpActive) {
            "Active (192.168.49.100–.240 • GW $groupOwnerIp)"
        } else {
            "Disabled (Static IP 192.168.49.100 / GW $groupOwnerIp)"
        }

        when (mode) {
            NatRoutingMode.IPTABLES_NAT -> {
                val ok = applyIptablesAndPolicyRouting(
                    p2pIface = cleanP2p,
                    upstreamIface = cleanUpstream,
                    groupOwnerIp = groupOwnerIp,
                    dnsPort = dnsPort,
                    dhcpPort = dhcpPort,
                    upstreamDns = upstreamDns,
                    speedLimit = speedLimit
                )
                isNatActive = ok
                if (ok) {
                    RoutingResult(true, "Root Kernel NAT ($cleanP2p -> $cleanUpstream)", isDhcpActive, dhcpStatusText)
                } else {
                    RoutingResult(
                        true,
                        "Non-Root Gateway ($groupOwnerIp:8282)",
                        isDhcpActive,
                        dhcpStatusText
                    )
                }
            }

            NatRoutingMode.NDC_TETHER -> {
                val ok = applyNdcTethering(cleanP2p, cleanUpstream, groupOwnerIp, dhcpPort, speedLimit)
                isNatActive = ok
                if (ok) {
                    RoutingResult(true, "System ndc NAT ($cleanP2p -> $cleanUpstream)", isDhcpActive, dhcpStatusText)
                } else {
                    RoutingResult(
                        true,
                        "Non-Root Gateway ($groupOwnerIp:8282)",
                        isDhcpActive,
                        dhcpStatusText
                    )
                }
            }

            NatRoutingMode.GATEWAY_DNS_ONLY -> {
                isNatActive = true
                RoutingResult(
                    true,
                    "Gateway $groupOwnerIp:8282 + DNS",
                    isDhcpActive,
                    dhcpStatusText
                )
            }

            NatRoutingMode.AUTO -> {
                if (applyIptablesAndPolicyRouting(
                        p2pIface = cleanP2p,
                        upstreamIface = cleanUpstream,
                        groupOwnerIp = groupOwnerIp,
                        dnsPort = dnsPort,
                        dhcpPort = dhcpPort,
                        upstreamDns = upstreamDns,
                        speedLimit = speedLimit
                    )
                ) {
                    isNatActive = true
                    return@withContext RoutingResult(
                        true,
                        "Root Kernel NAT ($cleanP2p -> $cleanUpstream)",
                        isDhcpActive,
                        dhcpStatusText
                    )
                }
                if (applyNdcTethering(cleanP2p, cleanUpstream, groupOwnerIp, dhcpPort, speedLimit)) {
                    isNatActive = true
                    return@withContext RoutingResult(
                        true,
                        "System ndc NAT ($cleanP2p -> $cleanUpstream)",
                        isDhcpActive,
                        dhcpStatusText
                    )
                }
                isNatActive = true
                onLog("No root detected -> Non-Root Gateway active on $groupOwnerIp:8282 (On 2nd phone set Wi-Fi Proxy to $groupOwnerIp port 8282 or Auto-Config http://$groupOwnerIp:8282/pac)")
                RoutingResult(
                    true,
                    "Non-Root Gateway ($groupOwnerIp:8282)",
                    isDhcpActive,
                    dhcpStatusText
                )
            }
        }
    }

    /**
     * Tears down NAT, DHCP, DNS, Userspace Gateway, and tc speed limit rules when the hotspot stops.
     */
    suspend fun disableRouting() = withContext(Dispatchers.IO) {
        dnsForwarder.stop()
        dhcpServer.stop()
        userspaceGateway.stop()
        val p2p = activeP2pInterface
        val upstream = activeUpstreamInterface

        if (p2p != null && upstream != null && isRootAvailable()) {
            val cleanupCommands = listOf(
                "iptables -t nat -D POSTROUTING -s $P2P_SUBNET -o $upstream -j MASQUERADE 2>/dev/null || true",
                "iptables -t filter -D FORWARD -i $p2p -o $upstream -j ACCEPT 2>/dev/null || true",
                "iptables -t filter -D FORWARD -i $upstream -o $p2p -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || true",
                "iptables -t filter -D tetherctrl_FORWARD -i $p2p -o $upstream -j ACCEPT 2>/dev/null || true",
                "iptables -t filter -D tetherctrl_FORWARD -i $upstream -o $p2p -j ACCEPT 2>/dev/null || true",
                "iptables -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true",
                "iptables -t nat -D PREROUTING -i $p2p -p udp --dport 53 -j REDIRECT --to-ports ${DnsForwarder.FALLBACK_DNS_PORT} 2>/dev/null || true",
                "iptables -t nat -D PREROUTING -i $p2p -p udp --dport 67 -j REDIRECT --to-ports ${DhcpServerEngine.FALLBACK_DHCP_PORT} 2>/dev/null || true",
                "iptables -t nat -D POSTROUTING -o $p2p -p udp --sport ${DhcpServerEngine.FALLBACK_DHCP_PORT} -j SNAT --to-source 192.168.49.1:67 2>/dev/null || true",
                "ip rule del to $P2P_SUBNET pref 5000 2>/dev/null || true",
                "ip rule del from $P2P_SUBNET pref 5001 2>/dev/null || true",
                "ip rule del iif $p2p pref 5002 2>/dev/null || true",
                "tc qdisc del dev $p2p root 2>/dev/null || true",
                "ndc nat disable $p2p $upstream 0 2>/dev/null || true",
                "ndc ipfwd disable netshare 2>/dev/null || true"
            )
            runRootCommands(cleanupCommands)
            onLog("Cleaned up NAT, DHCP, and routing rules for $p2p")
        }

        activeP2pInterface = null
        activeUpstreamInterface = null
        isNatActive = false
        isDhcpActive = false
    }

    private fun applyIptablesAndPolicyRouting(
        p2pIface: String,
        upstreamIface: String,
        groupOwnerIp: String,
        dnsPort: Int,
        dhcpPort: Int,
        upstreamDns: String,
        speedLimit: SpeedLimitPreset
    ): Boolean {
        if (!isRootAvailable()) {
            return false
        }

        onLog("Applying Root Kernel NAT, DHCP & high-priority policy routing: $p2pIface -> $upstreamIface")

        val dnsRule = if (dnsPort == DnsForwarder.FALLBACK_DNS_PORT) {
            "iptables -t nat -I PREROUTING -i $p2pIface -p udp --dport 53 -j DNAT --to-destination $groupOwnerIp:$dnsPort"
        } else {
            "iptables -t nat -I PREROUTING -i $p2pIface -p udp --dport 53 -j DNAT --to-destination $upstreamDns:53"
        }

        val dhcpRules = if (dhcpPort == DhcpServerEngine.FALLBACK_DHCP_PORT) {
            listOf(
                "iptables -t nat -D PREROUTING -i $p2pIface -p udp --dport 67 -j REDIRECT --to-ports $dhcpPort 2>/dev/null || true",
                "iptables -t nat -I PREROUTING -i $p2pIface -p udp --dport 67 -j REDIRECT --to-ports $dhcpPort",
                "iptables -t filter -I INPUT -i $p2pIface -p udp --dport $dhcpPort -j ACCEPT",
                "iptables -t filter -I INPUT -i $p2pIface -p udp --dport 67:68 -j ACCEPT",
                "iptables -t filter -I OUTPUT -o $p2pIface -p udp --sport 67:68 -j ACCEPT",
                "iptables -t nat -D POSTROUTING -o $p2pIface -p udp --sport $dhcpPort -j SNAT --to-source $groupOwnerIp:67 2>/dev/null || true",
                "iptables -t nat -I POSTROUTING -o $p2pIface -p udp --sport $dhcpPort -j SNAT --to-source $groupOwnerIp:67"
            )
        } else {
            listOf(
                "iptables -t filter -I INPUT -i $p2pIface -p udp --dport 67:68 -j ACCEPT",
                "iptables -t filter -I OUTPUT -o $p2pIface -p udp --sport 67:68 -j ACCEPT"
            )
        }

        val tcRules = buildTrafficControlCommands(p2pIface, speedLimit)

        val commands = mutableListOf(
            // Ensure P2P interface has 192.168.49.1/24 assigned and is UP
            "ip addr add $groupOwnerIp/24 broadcast 192.168.49.255 dev $p2pIface 2>/dev/null || true",
            "ip link set $p2pIface up 2>/dev/null || true",

            // Enable IPv4 forwarding in kernel and disable rp_filter on P2P + upstream
            "echo 1 > /proc/sys/net/ipv4/ip_forward",
            "sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || true",
            "echo 0 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true",
            "echo 0 > /proc/sys/net/ipv4/conf/$p2pIface/rp_filter 2>/dev/null || true",
            "echo 0 > /proc/sys/net/ipv4/conf/$upstreamIface/rp_filter 2>/dev/null || true",

            // Clean up any stale rules before inserting
            "iptables -t nat -D POSTROUTING -s $P2P_SUBNET -o $upstreamIface -j MASQUERADE 2>/dev/null || true",
            "iptables -t filter -D FORWARD -i $p2pIface -o $upstreamIface -j ACCEPT 2>/dev/null || true",
            "iptables -t filter -D FORWARD -i $upstreamIface -o $p2pIface -j ACCEPT 2>/dev/null || true",

            // Insert NAT masquerading & FORWARD rules (including Android's tetherctrl_FORWARD & bw_FORWARD)
            "iptables -P FORWARD ACCEPT 2>/dev/null || true",
            "iptables -t nat -I POSTROUTING 1 -s $P2P_SUBNET -o $upstreamIface -j MASQUERADE",
            "iptables -t filter -I FORWARD 1 -i $p2pIface -o $upstreamIface -j ACCEPT",
            "iptables -t filter -I FORWARD 1 -i $upstreamIface -o $p2pIface -j ACCEPT",
            "iptables -t filter -I bw_FORWARD 1 -i $p2pIface -j ACCEPT 2>/dev/null || true",
            "iptables -t filter -I bw_FORWARD 1 -o $p2pIface -j ACCEPT 2>/dev/null || true",
            "iptables -t filter -I fw_FORWARD 1 -i $p2pIface -j ACCEPT 2>/dev/null || true",
            "iptables -t filter -I fw_FORWARD 1 -o $p2pIface -j ACCEPT 2>/dev/null || true",
            "iptables -t filter -I tetherctrl_FORWARD 1 -i $p2pIface -o $upstreamIface -j ACCEPT 2>/dev/null || true",
            "iptables -t filter -I tetherctrl_FORWARD 1 -i $upstreamIface -o $p2pIface -j ACCEPT 2>/dev/null || true",
            "iptables -t mangle -I FORWARD 1 -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true",

            // Redirect DNS queries arriving on the P2P interface
            dnsRule,
            "iptables -t nat -I PREROUTING -i $p2pIface -p tcp --dport 53 -j DNAT --to-destination $upstreamDns:53 2>/dev/null || true",

            // Dynamically detect the numeric routing table ID of $upstreamIface and insert rules at pref 5000..5002
            // (BEFORE Android's netd fwmark rules at pref 10000+)
            "UPSTREAM_TABLE=\$(ip route show table all dev $upstreamIface 2>/dev/null | grep -o 'table [^ ]*' | awk '{print \$2}' | head -n 1)",
            "if [ -z \"\$UPSTREAM_TABLE\" ]; then UPSTREAM_TABLE=$upstreamIface; fi",
            "ip rule del to $P2P_SUBNET pref 5000 2>/dev/null || true",
            "ip rule del from $P2P_SUBNET pref 5001 2>/dev/null || true",
            "ip rule del iif $p2pIface pref 5002 2>/dev/null || true",
            "ip route replace $P2P_SUBNET dev $p2pIface scope link table local_network 2>/dev/null || true",
            "ip route replace $P2P_SUBNET dev $p2pIface scope link table \"\$UPSTREAM_TABLE\" 2>/dev/null || true",
            "ip rule add from all to $P2P_SUBNET lookup local_network pref 5000 2>/dev/null || true",
            "ip rule add from $P2P_SUBNET lookup \"\$UPSTREAM_TABLE\" pref 5001 2>/dev/null || ip rule add from $P2P_SUBNET lookup main pref 5001 2>/dev/null || true",
            "ip rule add iif $p2pIface lookup \"\$UPSTREAM_TABLE\" pref 5002 2>/dev/null || true",
            "ip route flush cache 2>/dev/null || true"
        )
        commands.addAll(dhcpRules)
        commands.addAll(tcRules)

        val ok = runRootCommands(commands)
        if (ok) {
            onLog("Root Kernel NAT, FORWARD chains & pref-5000 policy routing active ($p2pIface -> $upstreamIface)")
        }
        return ok
    }

    private fun applyNdcTethering(
        p2pIface: String,
        upstreamIface: String,
        groupOwnerIp: String,
        dhcpPort: Int,
        speedLimit: SpeedLimitPreset
    ): Boolean {
        if (!isRootAvailable()) return false
        val commands = mutableListOf(
            "ndc ipfwd enable netshare",
            "ndc tether interface add $p2pIface",
            "ndc nat enable $p2pIface $upstreamIface 1 $P2P_SUBNET"
        )
        if (dhcpPort == DhcpServerEngine.FALLBACK_DHCP_PORT) {
            commands.add("iptables -t nat -I PREROUTING -i $p2pIface -p udp --dport 67 -j REDIRECT --to-ports $dhcpPort 2>/dev/null || true")
            commands.add("iptables -t nat -I POSTROUTING -o $p2pIface -p udp --sport $dhcpPort -j SNAT --to-source $groupOwnerIp:67 2>/dev/null || true")
        }
        commands.addAll(buildTrafficControlCommands(p2pIface, speedLimit))
        val ok = runRootCommands(commands)
        if (ok) {
            onLog("ndc native tethering enabled ($p2pIface -> $upstreamIface)")
        }
        return ok
    }

    private fun buildTrafficControlCommands(
        p2pIface: String,
        speedLimit: SpeedLimitPreset
    ): List<String> {
        if (speedLimit == SpeedLimitPreset.UNLIMITED || speedLimit.rateKbps <= 0) {
            return listOf("tc qdisc del dev $p2pIface root 2>/dev/null || true")
        }
        val kbps = speedLimit.rateKbps
        onLog("Applying speed limit on $p2pIface: ${speedLimit.label} ($kbps kbps)")
        return listOf(
            "tc qdisc del dev $p2pIface root 2>/dev/null || true",
            "tc qdisc add dev $p2pIface root tbf rate ${kbps}kbit burst 64kbit latency 50ms 2>/dev/null || true"
        )
    }

    fun readArpTable(): Map<String, String> {
        val macToIp = mutableMapOf<String, String>()

        dhcpServer.getActiveLeases().forEach { (mac, lease) ->
            macToIp[mac.lowercase()] = lease.ipAddress
        }

        try {
            val arpFile = File("/proc/net/arp")
            if (arpFile.exists() && arpFile.canRead()) {
                arpFile.useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 4) {
                            val ip = parts[0]
                            val mac = parts[3].lowercase()
                            if (mac != "00:00:00:00:00:00" && ip.startsWith("192.168.49.")) {
                                macToIp[mac] = ip
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        try {
            val proc = Runtime.getRuntime().exec(arrayOf("ip", "neigh"))
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                reader.forEachLine { line ->
                    val tokens = line.trim().split(Regex("\\s+"))
                    val ip = tokens.firstOrNull() ?: return@forEachLine
                    val lladdrIdx = tokens.indexOf("lladdr")
                    if (ip.startsWith("192.168.49.") && lladdrIdx != -1 && lladdrIdx + 1 < tokens.size) {
                        val mac = tokens[lladdrIdx + 1].lowercase()
                        macToIp[mac] = ip
                    }
                }
            }
        } catch (_: Exception) {
        }
        return macToIp
    }

    private fun isRootAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id").start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runRootCommands(commands: List<String>): Boolean {
        return try {
            val process = ProcessBuilder("su").redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { writer ->
                for (cmd in commands) {
                    writer.write(cmd)
                    writer.newLine()
                }
                writer.write("exit")
                writer.newLine()
                writer.flush()
            }
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            onLog("Root command error: ${e.message}")
            false
        }
    }

    companion object {
        const val P2P_SUBNET = "192.168.49.0/24"
    }
}
