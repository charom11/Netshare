package com.netshare.wifidirect.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure Kotlin RFC 2131 DHCPv4 Server for the Wi-Fi Direct Group Owner (`192.168.49.1/24`).
 *
 * Ensures that any 2nd phone or laptop connecting via the standard Wi-Fi scan list
 * immediately receives:
 * - Client IPv4 lease (`192.168.49.100` – `192.168.49.240`)
 * - Subnet Mask (`255.255.255.0`, Option 1)
 * - Default Gateway / Router (`192.168.49.1`, Option 3)
 * - DNS Servers (`192.168.49.1` and `8.8.8.8`, Option 6)
 * - Broadcast Address (`192.168.49.255`, Option 28)
 */
class DhcpServerEngine(
    private val onLog: (String) -> Unit,
    private val onLeaseUpdated: (DhcpLease) -> Unit = {}
) {
    data class DhcpLease(
        val macAddress: String,
        val ipAddress: String,
        val hostName: String,
        val timestampMs: Long
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var socket: DatagramSocket? = null

    private val leasesByMac = ConcurrentHashMap<String, DhcpLease>()
    private val macByLastOctet = ConcurrentHashMap<Int, String>()

    @Volatile
    var boundPort: Int = FALLBACK_DHCP_PORT
        private set

    fun getActiveLeases(): Map<String, DhcpLease> = leasesByMac.toMap()

    fun start(
        groupOwnerIp: String = "192.168.49.1",
        p2pInterface: String = "p2p-wlan0-0",
        primaryDns: String = "8.8.8.8"
    ): Int {
        stop()

        val dhcpSocket = try {
            // Try binding directly to standard DHCP UDP port 67
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress("0.0.0.0", STANDARD_DHCP_SERVER_PORT))
            }.also {
                boundPort = STANDARD_DHCP_SERVER_PORT
                onLog("DHCP server listening directly on UDP port $STANDARD_DHCP_SERVER_PORT (Pool: 192.168.49.100-.240)")
            }
        } catch (_: Exception) {
            // Bind to unprivileged UDP port 6767 (iptables PREROUTING redirects 67 -> 6767)
            try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress("0.0.0.0", FALLBACK_DHCP_PORT))
                }.also {
                    boundPort = FALLBACK_DHCP_PORT
                    onLog("DHCP server listening on UDP port $FALLBACK_DHCP_PORT (Pool: 192.168.49.100-.240, Gateway: $groupOwnerIp)")
                }
            } catch (e: Exception) {
                onLog("DHCP server socket bind failed: ${e.message}")
                return -1
            }
        }

        socket = dhcpSocket
        serverJob = scope.launch {
            val buf = ByteArray(1500)
            while (isActive && !dhcpSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    dhcpSocket.receive(packet)
                    val requestBytes = packet.data.copyOfRange(0, packet.length)
                    handleDhcpPacket(
                        listenSocket = dhcpSocket,
                        data = requestBytes,
                        groupOwnerIp = groupOwnerIp,
                        p2pInterface = p2pInterface,
                        primaryDns = primaryDns
                    )
                } catch (_: Exception) {
                    if (dhcpSocket.isClosed) break
                }
            }
        }
        return boundPort
    }

    private fun handleDhcpPacket(
        listenSocket: DatagramSocket,
        data: ByteArray,
        groupOwnerIp: String,
        p2pInterface: String,
        primaryDns: String
    ) {
        // Minimum BOOTP header is 236 bytes + 4-byte magic cookie
        if (data.size < 240) return
        val op = data[0].toInt() and 0xFF
        if (op != BOOTREQUEST) return

        val htype = data[1].toInt() and 0xFF
        val hlen = (data[2].toInt() and 0xFF).coerceIn(1, 16)
        val xid = ByteBuffer.wrap(data, 4, 4).int

        val chaddr = data.copyOfRange(28, 44)
        val macAddress = chaddr.take(hlen).joinToString(":") { "%02x".format(it) }.lowercase()

        // Verify DHCP magic cookie 0x63, 0x82, 0x53, 0x63 at offset 236
        if ((data[236].toInt() and 0xFF) != 99 ||
            (data[237].toInt() and 0xFF) != 130 ||
            (data[238].toInt() and 0xFF) != 83 ||
            (data[239].toInt() and 0xFF) != 99
        ) {
            return
        }

        var messageType = -1
        var requestedIp: String? = null
        var clientHostName = "Android-Client"

        var idx = 240
        while (idx < data.size) {
            val optCode = data[idx].toInt() and 0xFF
            idx++
            if (optCode == OPT_PAD) continue
            if (optCode == OPT_END) break
            if (idx >= data.size) break
            val optLen = data[idx].toInt() and 0xFF
            idx++
            if (idx + optLen > data.size) break

            val optVal = data.copyOfRange(idx, idx + optLen)
            when (optCode) {
                OPT_MESSAGE_TYPE -> {
                    if (optVal.isNotEmpty()) {
                        messageType = optVal[0].toInt() and 0xFF
                    }
                }
                OPT_REQUESTED_IP -> {
                    if (optVal.size == 4) {
                        requestedIp = "${optVal[0].toInt() and 0xFF}.${optVal[1].toInt() and 0xFF}.${optVal[2].toInt() and 0xFF}.${optVal[3].toInt() and 0xFF}"
                    }
                }
                OPT_HOSTNAME -> {
                    val parsedName = String(optVal, Charsets.UTF_8).trim()
                    if (parsedName.isNotEmpty()) {
                        clientHostName = parsedName
                    }
                }
            }
            idx += optLen
        }

        when (messageType) {
            DHCP_DISCOVER -> {
                val offeredIp = allocateIpForMac(macAddress, requestedIp, clientHostName)
                onLog("DHCPDISCOVER from $macAddress ($clientHostName) -> Offering $offeredIp (GW $groupOwnerIp)")
                val reply = buildDhcpReply(
                    replyType = DHCP_OFFER,
                    htype = htype,
                    hlen = hlen,
                    xid = xid,
                    chaddr = chaddr,
                    yourIp = offeredIp,
                    serverIp = groupOwnerIp,
                    primaryDns = primaryDns
                )
                sendDhcpReply(listenSocket, reply, offeredIp, macAddress, p2pInterface)
            }

            DHCP_REQUEST, DHCP_INFORM -> {
                val assignedIp = allocateIpForMac(macAddress, requestedIp, clientHostName)
                val lease = DhcpLease(
                    macAddress = macAddress,
                    ipAddress = assignedIp,
                    hostName = clientHostName,
                    timestampMs = System.currentTimeMillis()
                )
                leasesByMac[macAddress] = lease
                onLeaseUpdated(lease)

                onLog("DHCPREQUEST from $macAddress ($clientHostName) -> ACK $assignedIp (Gateway & DNS: $groupOwnerIp)")
                val reply = buildDhcpReply(
                    replyType = DHCP_ACK,
                    htype = htype,
                    hlen = hlen,
                    xid = xid,
                    chaddr = chaddr,
                    yourIp = assignedIp,
                    serverIp = groupOwnerIp,
                    primaryDns = primaryDns
                )
                sendDhcpReply(listenSocket, reply, assignedIp, macAddress, p2pInterface)
            }
        }
    }

    private fun allocateIpForMac(
        macAddress: String,
        requestedIp: String?,
        hostName: String
    ): String {
        leasesByMac[macAddress]?.let { existing ->
            return existing.ipAddress
        }

        // If client requested a valid 192.168.49.x IP in our range and it's free, honor it
        if (requestedIp != null && requestedIp.startsWith("192.168.49.")) {
            val lastOctet = requestedIp.substringAfterLast(".").toIntOrNull()
            if (lastOctet != null && lastOctet in 2..250) {
                val owner = macByLastOctet[lastOctet]
                if (owner == null || owner == macAddress) {
                    macByLastOctet[lastOctet] = macAddress
                    val ip = "192.168.49.$lastOctet"
                    leasesByMac[macAddress] = DhcpLease(macAddress, ip, hostName, System.currentTimeMillis())
                    return ip
                }
            }
        }

        // Deterministic hash from MAC in 100..240 range
        val baseOctet = 100 + (macAddress.hashCode().and(0x7FFFFFFF) % 140)
        for (offset in 0 until 140) {
            val candidateOctet = 100 + ((baseOctet - 100 + offset) % 140)
            val owner = macByLastOctet.putIfAbsent(candidateOctet, macAddress)
            if (owner == null || owner == macAddress) {
                val ip = "192.168.49.$candidateOctet"
                leasesByMac[macAddress] = DhcpLease(macAddress, ip, hostName, System.currentTimeMillis())
                return ip
            }
        }

        return "192.168.49.100"
    }

    private fun buildDhcpReply(
        replyType: Int,
        htype: Int,
        hlen: Int,
        xid: Int,
        chaddr: ByteArray,
        yourIp: String,
        serverIp: String,
        primaryDns: String
    ): ByteArray {
        val out = ByteArrayOutputStream(320)
        val header = ByteBuffer.allocate(240)

        header.put(BOOTREPLY.toByte()) // op = 2
        header.put(htype.toByte())
        header.put(hlen.toByte())
        header.put(0.toByte()) // hops
        header.putInt(xid)
        header.putShort(0.toShort()) // secs
        header.putShort(0x8000.toShort()) // broadcast flag so client receives reply before IP is configured
        header.putInt(0) // ciaddr
        header.put(ipToBytes(yourIp)) // yiaddr (assigned client IP)
        header.put(ipToBytes(serverIp)) // siaddr (next server IP)
        header.putInt(0) // giaddr
        header.put(chaddr, 0, 16) // chaddr (16 bytes)
        header.put(ByteArray(64)) // sname
        header.put(ByteArray(128)) // file

        // Magic Cookie: 99, 130, 83, 99
        header.put(byteArrayOf(99.toByte(), 130.toByte(), 83.toByte(), 99.toByte()))
        out.write(header.array())

        // Option 53: DHCP Message Type (OFFER=2 or ACK=5)
        writeOption(out, OPT_MESSAGE_TYPE, byteArrayOf(replyType.toByte()))

        // Option 54: DHCP Server Identifier (192.168.49.1)
        writeOption(out, OPT_SERVER_ID, ipToBytes(serverIp))

        // Option 51: Lease Time (43200s = 12 hours)
        writeOption(out, OPT_LEASE_TIME, intToBytes(43200))

        // Option 58: Renewal Time T1 (21600s)
        writeOption(out, OPT_RENEWAL_TIME, intToBytes(21600))

        // Option 59: Rebinding Time T2 (37800s)
        writeOption(out, OPT_REBINDING_TIME, intToBytes(37800))

        // Option 1: Subnet Mask (255.255.255.0)
        writeOption(out, OPT_SUBNET_MASK, byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0.toByte()))

        // Option 28: Broadcast Address (192.168.49.255)
        writeOption(out, OPT_BROADCAST_ADDR, byteArrayOf(192.toByte(), 168.toByte(), 49.toByte(), 255.toByte()))

        // Option 3: Router / Default Gateway (192.168.49.1)
        writeOption(out, OPT_ROUTER, ipToBytes(serverIp))

        // Option 6: DNS Servers (192.168.49.1 + upstream DNS e.g. 8.8.8.8)
        val dnsBytes = ByteArrayOutputStream(8).apply {
            write(ipToBytes(serverIp))
            write(ipToBytes(primaryDns))
        }.toByteArray()
        writeOption(out, OPT_DNS_SERVERS, dnsBytes)

        // Option 252: WPAD Proxy Auto-Config URL for non-root gateway fallback
        val wpadUrl = "http://$serverIp:${UserspaceGatewayServer.GATEWAY_PORT}/pac".toByteArray(Charsets.US_ASCII)
        writeOption(out, 252, wpadUrl)

        // Option 255: End
        out.write(OPT_END)

        // Pad to minimum 300 bytes for strict BOOTP clients
        while (out.size() < 300) {
            out.write(OPT_PAD)
        }
        return out.toByteArray()
    }

    private fun sendDhcpReply(
        listenSocket: DatagramSocket,
        replyBytes: ByteArray,
        clientIp: String,
        clientMac: String,
        p2pInterface: String
    ) {
        // Seed ARP table entry when possible so unicast also succeeds
        try {
            Runtime.getRuntime().exec(
                arrayOf("ip", "neigh", "replace", clientIp, "lladdr", clientMac, "dev", p2pInterface, "nud", "reachable")
            )
        } catch (_: Exception) {
        }

        // 1. Send to subnet broadcast 192.168.49.255:68
        try {
            val subnetBcast = InetAddress.getByName("192.168.49.255")
            listenSocket.send(DatagramPacket(replyBytes, replyBytes.size, subnetBcast, STANDARD_DHCP_CLIENT_PORT))
        } catch (_: Exception) {
        }

        // 2. Send to global broadcast 255.255.255.255:68
        try {
            val globalBcast = InetAddress.getByName("255.255.255.255")
            listenSocket.send(DatagramPacket(replyBytes, replyBytes.size, globalBcast, STANDARD_DHCP_CLIENT_PORT))
        } catch (_: Exception) {
        }
    }

    private fun writeOption(out: ByteArrayOutputStream, code: Int, payload: ByteArray) {
        out.write(code)
        out.write(payload.size)
        out.write(payload)
    }

    private fun ipToBytes(ip: String): ByteArray {
        return try {
            InetAddress.getByName(ip).address
        } catch (_: Exception) {
            byteArrayOf(192.toByte(), 168.toByte(), 49.toByte(), 1.toByte())
        }
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).putInt(value).array()
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    companion object {
        const val STANDARD_DHCP_SERVER_PORT = 67
        const val STANDARD_DHCP_CLIENT_PORT = 68
        const val FALLBACK_DHCP_PORT = 6767

        private const val BOOTREQUEST = 1
        private const val BOOTREPLY = 2

        private const val DHCP_DISCOVER = 1
        private const val DHCP_OFFER = 2
        private const val DHCP_REQUEST = 3
        private const val DHCP_ACK = 5
        private const val DHCP_INFORM = 8

        private const val OPT_PAD = 0
        private const val OPT_SUBNET_MASK = 1
        private const val OPT_ROUTER = 3
        private const val OPT_DNS_SERVERS = 6
        private const val OPT_HOSTNAME = 12
        private const val OPT_BROADCAST_ADDR = 28
        private const val OPT_REQUESTED_IP = 50
        private const val OPT_LEASE_TIME = 51
        private const val OPT_MESSAGE_TYPE = 53
        private const val OPT_SERVER_ID = 54
        private const val OPT_RENEWAL_TIME = 58
        private const val OPT_REBINDING_TIME = 59
        private const val OPT_END = 255
    }
}

