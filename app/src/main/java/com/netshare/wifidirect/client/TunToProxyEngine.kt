package com.netshare.wifidirect.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure Kotlin Userspace Tunnel that intercepts raw packets from the VPN TUN interface
 * and bridges them to the NetShare Direct Gateway at 192.168.49.1:8282.
 *
 * Key fixes for "Connected but no internet":
 * 1. DNS queries go DIRECTLY to 8.8.8.8 (or 1.1.1.1 fallback) via a protected UDP socket,
 *    bypassing the gateway entirely — the Host phone's DNS Forwarder may be on port 5353
 *    (not 53) when non-root, so sending to gateway:53 would fail silently.
 * 2. TCP response data from the proxy is segmented to fit inside TUN MTU (max ~1400 bytes
 *    per IP packet), preventing silent drops of oversized packets.
 * 3. Better error logging and connection retry logic.
 */
class TunToProxyEngine(
    private val tunFd: FileDescriptor,
    private val gatewayHost: String = "192.168.49.1",
    private val gatewayPort: Int = 8282,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean,
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engineJob: Job? = null

    private val tunIn = FileInputStream(tunFd)
    private val tunOut = FileOutputStream(tunFd)

    val totalRxBytes = AtomicLong(0L)
    val totalTxBytes = AtomicLong(0L)

    private val tcpConnections = ConcurrentHashMap<String, TcpSession>()

    // DNS servers to query directly (bypassing the gateway)
    private val dnsServers = listOf("8.8.8.8", "1.1.1.1")

    // Max TCP payload per TUN write to avoid exceeding MTU
    private val maxTcpSegmentPayload = 1400

    private class TcpSession(
        val key: String,
        val srcIpBytes: ByteArray,
        val dstIpBytes: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        var clientSeq: Long,
        var serverSeq: Long,
        var socket: Socket? = null,
        var socketOut: OutputStream? = null,
        var isEstablished: Boolean = false,
        var isClosed: Boolean = false
    )

    fun start() {
        stop()
        onLog("TUN Engine started → Gateway $gatewayHost:$gatewayPort | DNS direct to ${dnsServers[0]}")

        engineJob = scope.launch {
            val buffer = ByteArray(32768)
            while (isActive) {
                try {
                    val readLen = tunIn.read(buffer)
                    if (readLen <= 0) break

                    totalRxBytes.addAndGet(readLen.toLong())
                    val ipPacket = PacketUtils.parseIp(buffer, readLen) ?: continue

                    // Skip traffic destined to the gateway proxy port itself (avoid loop)
                    if (ipPacket.dstIp == gatewayHost && ipPacket.ipPayloadOffset + 4 <= readLen) {
                        val dstPort = ((buffer[ipPacket.ipPayloadOffset + 2].toInt() and 0xFF) shl 8) or
                            (buffer[ipPacket.ipPayloadOffset + 3].toInt() and 0xFF)
                        if (dstPort == gatewayPort) continue
                    }

                    when (ipPacket.protocol) {
                        PacketUtils.PROTOCOL_ICMP -> {
                            val reply = PacketUtils.buildIcmpReply(buffer, readLen)
                            if (reply != null) {
                                sendToTun(reply)
                            }
                        }

                        PacketUtils.PROTOCOL_UDP -> {
                            val udpPacket = PacketUtils.parseUdp(buffer, ipPacket) ?: continue
                            if (udpPacket.dstPort == 53) {
                                // DNS — forward directly to public DNS via protected socket
                                val dnsQuery = buffer.copyOfRange(
                                    udpPacket.payloadOffset,
                                    udpPacket.payloadOffset + udpPacket.payloadLength
                                )
                                launch {
                                    forwardDnsDirect(ipPacket, udpPacket, dnsQuery)
                                }
                            }
                            // Other UDP: silently drop (VPN tunnel only handles TCP + DNS)
                        }

                        PacketUtils.PROTOCOL_TCP -> {
                            val tcpPacket = PacketUtils.parseTcp(buffer, ipPacket) ?: continue
                            handleTcpPacket(ipPacket, tcpPacket, buffer)
                        }
                    }
                } catch (_: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    private fun handleTcpPacket(
        ip: PacketUtils.ParsedIpPacket,
        tcp: PacketUtils.ParsedTcpPacket,
        rawPacket: ByteArray
    ) {
        val key = "${tcp.srcPort}:${ip.dstIp}:${tcp.dstPort}"

        // 1. SYN — new connection
        if ((tcp.flags and PacketUtils.TCP_FLAG_SYN) != 0 && (tcp.flags and PacketUtils.TCP_FLAG_ACK) == 0) {
            val initialServerSeq = (Math.random() * 1000000).toLong()
            val session = TcpSession(
                key = key,
                srcIpBytes = ip.srcIpBytes,
                dstIpBytes = ip.dstIpBytes,
                srcPort = tcp.srcPort,
                dstPort = tcp.dstPort,
                clientSeq = (tcp.seqNumber + 1) and 0xFFFFFFFFL,
                serverSeq = initialServerSeq
            )
            tcpConnections[key] = session

            // SYN-ACK back immediately
            val synAck = PacketUtils.buildTcpPacket(
                srcIp = ip.dstIpBytes,
                dstIp = ip.srcIpBytes,
                srcPort = tcp.dstPort,
                dstPort = tcp.srcPort,
                seqNumber = session.serverSeq,
                ackNumber = session.clientSeq,
                flags = PacketUtils.TCP_FLAG_SYN or PacketUtils.TCP_FLAG_ACK
            )
            session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
            sendToTun(synAck)

            // Connect upstream via HTTP CONNECT
            scope.launch {
                initiateProxyConnection(session, ip.dstIp, tcp.dstPort)
            }
            return
        }

        val session = tcpConnections[key] ?: return

        // 2. RST or FIN — close
        if ((tcp.flags and PacketUtils.TCP_FLAG_RST) != 0 || (tcp.flags and PacketUtils.TCP_FLAG_FIN) != 0) {
            closeSession(session, sendFin = true)
            return
        }

        // 3. Data payload — forward to gateway
        if (tcp.payloadLength > 0) {
            session.clientSeq = (tcp.seqNumber + tcp.payloadLength) and 0xFFFFFFFFL

            val ackPacket = PacketUtils.buildTcpPacket(
                srcIp = session.dstIpBytes,
                dstIp = session.srcIpBytes,
                srcPort = session.dstPort,
                dstPort = session.srcPort,
                seqNumber = session.serverSeq,
                ackNumber = session.clientSeq,
                flags = PacketUtils.TCP_FLAG_ACK
            )
            sendToTun(ackPacket)

            val payload = rawPacket.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength)
            scope.launch {
                try {
                    session.socketOut?.write(payload)
                    session.socketOut?.flush()
                } catch (_: Exception) {
                    closeSession(session, sendFin = true)
                }
            }
        }
    }

    private fun initiateProxyConnection(session: TcpSession, targetHost: String, targetPort: Int) {
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            socket.sendBufferSize = 65536
            socket.receiveBufferSize = 65536
            if (!protectSocket(socket)) {
                onLog("WARN: Failed to protect proxy socket for $targetHost:$targetPort")
            }

            socket.connect(InetSocketAddress(gatewayHost, gatewayPort), 10_000)
            session.socket = socket

            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            session.socketOut = out

            // HTTP CONNECT handshake
            val connectReq = "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n"
            out.write(connectReq.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            // Read response
            val respLine = readLine(input)
            if (respLine == null || !respLine.contains("200")) {
                onLog("CONNECT rejected for $targetHost:$targetPort → $respLine")
                closeSession(session, sendFin = true)
                return
            }
            // Consume remaining headers until blank line
            while (true) {
                val line = readLine(input)
                if (line.isNullOrEmpty()) break
            }

            session.isEstablished = true

            // Read upstream data and inject back into TUN as segmented TCP packets
            val buf = ByteArray(32768)
            while (!session.isClosed && socket.isConnected && !socket.isClosed) {
                val n = input.read(buf)
                if (n <= 0) break

                // Segment the response into MTU-safe chunks
                var offset = 0
                while (offset < n && !session.isClosed) {
                    val chunkSize = minOf(maxTcpSegmentPayload, n - offset)
                    val chunk = buf.copyOfRange(offset, offset + chunkSize)

                    val dataPacket = PacketUtils.buildTcpPacket(
                        srcIp = session.dstIpBytes,
                        dstIp = session.srcIpBytes,
                        srcPort = session.dstPort,
                        dstPort = session.srcPort,
                        seqNumber = session.serverSeq,
                        ackNumber = session.clientSeq,
                        flags = PacketUtils.TCP_FLAG_ACK or PacketUtils.TCP_FLAG_PSH,
                        payload = chunk
                    )
                    session.serverSeq = (session.serverSeq + chunkSize) and 0xFFFFFFFFL
                    sendToTun(dataPacket)
                    offset += chunkSize
                }
            }
        } catch (e: Exception) {
            onLog("Proxy connection error for ${session.key}: ${e.message}")
        } finally {
            closeSession(session, sendFin = true)
        }
    }

    /**
     * Forward DNS queries DIRECTLY to public DNS servers (8.8.8.8, 1.1.1.1)
     * via a VPN-protected UDP socket. This completely bypasses the gateway,
     * solving the problem where Phone 1's DNS forwarder binds to port 5353
     * (unreachable on port 53 without root iptables redirect).
     */
    private fun forwardDnsDirect(
        ip: PacketUtils.ParsedIpPacket,
        udp: PacketUtils.ParsedUdpPacket,
        dnsQuery: ByteArray
    ) {
        for (dnsServer in dnsServers) {
            var udpSocket: DatagramSocket? = null
            try {
                udpSocket = DatagramSocket()
                udpSocket.soTimeout = 3000
                protectDatagram(udpSocket)

                val targetDns = InetAddress.getByName(dnsServer)
                val outPacket = DatagramPacket(dnsQuery, dnsQuery.size, targetDns, 53)
                udpSocket.send(outPacket)

                val recvBuf = ByteArray(1500)
                val inPacket = DatagramPacket(recvBuf, recvBuf.size)
                udpSocket.receive(inPacket)

                val replyDns = inPacket.data.copyOf(inPacket.length)
                val replyIpPacket = PacketUtils.buildUdpPacket(
                    srcIp = ip.dstIpBytes,
                    dstIp = ip.srcIpBytes,
                    srcPort = udp.dstPort,
                    dstPort = udp.srcPort,
                    payload = replyDns
                )
                sendToTun(replyIpPacket)
                return // Success, don't try fallback
            } catch (_: Exception) {
                // Try next DNS server
            } finally {
                try { udpSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun sendToTun(packet: ByteArray) {
        try {
            synchronized(tunOut) {
                tunOut.write(packet)
                tunOut.flush()
            }
            totalTxBytes.addAndGet(packet.size.toLong())
        } catch (_: Exception) {
        }
    }

    private fun closeSession(session: TcpSession, sendFin: Boolean) {
        if (session.isClosed) return
        session.isClosed = true
        tcpConnections.remove(session.key)

        if (sendFin) {
            val finPacket = PacketUtils.buildTcpPacket(
                srcIp = session.dstIpBytes,
                dstIp = session.srcIpBytes,
                srcPort = session.dstPort,
                dstPort = session.srcPort,
                seqNumber = session.serverSeq,
                ackNumber = session.clientSeq,
                flags = PacketUtils.TCP_FLAG_FIN or PacketUtils.TCP_FLAG_ACK
            )
            sendToTun(finPacket)
        }

        try { session.socket?.close() } catch (_: Exception) {}
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isNotEmpty()) sb.toString() else null
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    fun stop() {
        engineJob?.cancel()
        engineJob = null
        tcpConnections.values.forEach { closeSession(it, sendFin = false) }
        tcpConnections.clear()
        try { tunIn.close() } catch (_: Exception) {}
        try { tunOut.close() } catch (_: Exception) {}
    }
}
