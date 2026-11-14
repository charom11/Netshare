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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure Kotlin Userspace Tunnel that intercepts raw packets from the VPN TUN interface
 * and bridges them to the NetShare Direct Gateway at 192.168.49.1:8282.
 *
 * Core Fixes:
 * 1. Payload Queuing: Data packets (TLS Client Hello, HTTP GET) arriving before the upstream
 *    HTTP CONNECT handshake completes are buffered in [TcpSession.pendingPayloads] and flushed
 *    in exact FIFO order as soon as the tunnel connects. No dropped requests!
 * 2. Multi-Path DNS: Resolves queries using:
 *    - UDP to Phone 1's local forwarder on ports 8253, 5353, 53.
 *    - DNS-over-TCP via HTTP CONNECT through the gateway (:8282) to 8.8.8.8:53 & 1.1.1.1:53.
 *    - In-memory DNS cache for instant sub-millisecond repeat resolutions.
 * 3. MTU-safe packet segmentation: All TCP return traffic is split into MTU-safe chunks (max 1400 bytes).
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
    private val dnsCache = ConcurrentHashMap<String, ByteArray>()

    private val maxTcpSegmentPayload = 1400

    private class TcpSession(
        val key: String,
        val srcIpBytes: ByteArray,
        val dstIpBytes: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        var clientSeq: Long,
        var serverSeq: Long,
        @Volatile var socket: Socket? = null,
        @Volatile var socketOut: OutputStream? = null,
        @Volatile var isEstablished: Boolean = false,
        @Volatile var isClosed: Boolean = false
    ) {
        val pendingPayloads = ConcurrentLinkedQueue<ByteArray>()
    }

    fun start() {
        stop()
        onLog("TUN-to-Proxy active → Gateway $gatewayHost:$gatewayPort (DNS: local + TCP tunnel)")

        engineJob = scope.launch {
            val buffer = ByteArray(32768)
            while (isActive) {
                try {
                    val readLen = tunIn.read(buffer)
                    if (readLen <= 0) break

                    totalRxBytes.addAndGet(readLen.toLong())
                    val ipPacket = PacketUtils.parseIp(buffer, readLen) ?: continue

                    // Ignore loopback or direct traffic destined to the gateway host on gatewayPort
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
                                val dnsQuery = buffer.copyOfRange(
                                    udpPacket.payloadOffset,
                                    udpPacket.payloadOffset + udpPacket.payloadLength
                                )
                                launch {
                                    resolveAndReplyDns(ipPacket, udpPacket, dnsQuery)
                                }
                            }
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

        // 1. SYN — connection initiation
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

            // Reply with SYN-ACK immediately so client OS considers connection OPEN
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

            // Connect upstream to proxy in background
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

        // 3. Data payload — ACK and queue for transmission
        if (tcp.payloadLength > 0) {
            session.clientSeq = (tcp.seqNumber + tcp.payloadLength) and 0xFFFFFFFFL

            // ACK the data packet back to TUN so client TCP stack knows it arrived
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
            session.pendingPayloads.add(payload)

            // Flush queue if socket is already connected
            flushSessionPayloads(session)
        }
    }

    private fun flushSessionPayloads(session: TcpSession) {
        if (!session.isEstablished || session.isClosed) return
        val out = session.socketOut ?: return

        scope.launch {
            try {
                synchronized(out) {
                    while (true) {
                        val chunk = session.pendingPayloads.poll() ?: break
                        out.write(chunk)
                    }
                    out.flush()
                }
            } catch (_: Exception) {
                closeSession(session, sendFin = true)
            }
        }
    }

    private fun initiateProxyConnection(session: TcpSession, targetHost: String, targetPort: Int) {
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 45_000
            socket.sendBufferSize = 65536
            socket.receiveBufferSize = 65536

            if (!protectSocket(socket)) {
                onLog("WARN: Socket protect returned false for $targetHost:$targetPort")
            }

            socket.connect(InetSocketAddress(gatewayHost, gatewayPort), 8000)
            session.socket = socket

            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            session.socketOut = out

            // Send HTTP CONNECT to NetShare Gateway
            val connectReq = "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n"
            out.write(connectReq.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            // Read HTTP 200 response line
            val respLine = readLine(input)
            if (respLine == null || !respLine.contains("200")) {
                closeSession(session, sendFin = true)
                return
            }

            // Consume remaining headers until empty line
            while (true) {
                val line = readLine(input)
                if (line.isNullOrEmpty()) break
            }

            session.isEstablished = true

            // Immediately flush any payloads buffered while CONNECT was in progress
            flushSessionPayloads(session)

            // Read data from upstream server and inject into TUN in MTU-safe segments
            val buf = ByteArray(32768)
            while (!session.isClosed && socket.isConnected && !socket.isClosed) {
                val n = input.read(buf)
                if (n <= 0) break

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
        } catch (_: Exception) {
        } finally {
            closeSession(session, sendFin = true)
        }
    }

    /**
     * Multi-Path DNS Resolver:
     * 1. Check in-memory cache.
     * 2. Try UDP to Phone 1 local DNS Forwarder (ports 8253, 5353, 53).
     * 3. Fallback to DNS-over-TCP via HTTP CONNECT through Gateway (:8282).
     */
    private suspend fun resolveAndReplyDns(
        ip: PacketUtils.ParsedIpPacket,
        udp: PacketUtils.ParsedUdpPacket,
        dnsQuery: ByteArray
    ) {
        if (dnsQuery.size < 12) return

        // Cache key based on query payload (ignoring 2-byte transaction ID)
        val querySignature = dnsQuery.copyOfRange(2, dnsQuery.size).contentHashCode().toString()
        val transactionId = dnsQuery.copyOfRange(0, 2)

        val cachedAnswer = dnsCache[querySignature]
        if (cachedAnswer != null && cachedAnswer.size >= 12) {
            val reply = cachedAnswer.clone()
            reply[0] = transactionId[0]
            reply[1] = transactionId[1]
            val replyPacket = PacketUtils.buildUdpPacket(
                srcIp = ip.dstIpBytes,
                dstIp = ip.srcIpBytes,
                srcPort = udp.dstPort,
                dstPort = udp.srcPort,
                payload = reply
            )
            sendToTun(replyPacket)
            return
        }

        // Path A: Try UDP to Phone 1 local forwarder ports (8253, 5353, 53)
        val localPorts = intArrayOf(8253, 5353, 53)
        for (port in localPorts) {
            val reply = queryDnsUdp(gatewayHost, port, dnsQuery)
            if (reply != null) {
                dnsCache[querySignature] = reply
                val replyPacket = PacketUtils.buildUdpPacket(
                    srcIp = ip.dstIpBytes,
                    dstIp = ip.srcIpBytes,
                    srcPort = udp.dstPort,
                    dstPort = udp.srcPort,
                    payload = reply
                )
                sendToTun(replyPacket)
                return
            }
        }

        // Path B: Fallback to DNS-over-TCP via HTTP CONNECT through the Gateway (:8282)
        val publicDnsServers = listOf("8.8.8.8", "1.1.1.1")
        for (dnsIp in publicDnsServers) {
            val reply = queryDnsOverTcpThroughProxy(dnsIp, 53, dnsQuery)
            if (reply != null) {
                dnsCache[querySignature] = reply
                val replyPacket = PacketUtils.buildUdpPacket(
                    srcIp = ip.dstIpBytes,
                    dstIp = ip.srcIpBytes,
                    srcPort = udp.dstPort,
                    dstPort = udp.srcPort,
                    payload = reply
                )
                sendToTun(replyPacket)
                return
            }
        }
    }

    private fun queryDnsUdp(host: String, port: Int, query: ByteArray): ByteArray? {
        var sock: DatagramSocket? = null
        return try {
            sock = DatagramSocket()
            sock.soTimeout = 1200
            protectDatagram(sock)

            val dest = InetAddress.getByName(host)
            val packet = DatagramPacket(query, query.size, dest, port)
            sock.send(packet)

            val buf = ByteArray(2048)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            buf.copyOf(resp.length)
        } catch (_: Exception) {
            null
        } finally {
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Queries public DNS (8.8.8.8:53) over TCP tunnel through the gateway.
     * RFC 1035 TCP DNS format: 2-byte big-endian length prefix followed by query.
     */
    private fun queryDnsOverTcpThroughProxy(dnsHost: String, dnsPort: Int, query: ByteArray): ByteArray? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = 3000
            protectSocket(socket)

            socket.connect(InetSocketAddress(gatewayHost, gatewayPort), 2500)
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // CONNECT handshake
            val req = "CONNECT $dnsHost:$dnsPort HTTP/1.1\r\nHost: $dnsHost:$dnsPort\r\n\r\n"
            out.write(req.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            val resp = readLine(input)
            if (resp == null || !resp.contains("200")) return null

            while (true) {
                val line = readLine(input)
                if (line.isNullOrEmpty()) break
            }

            // Write 2-byte length prefix + query
            val qLen = query.size
            out.write(byteArrayOf((qLen shr 8).toByte(), (qLen and 0xFF).toByte()))
            out.write(query)
            out.flush()

            // Read 2-byte response length
            val b1 = input.read()
            val b2 = input.read()
            if (b1 == -1 || b2 == -1) return null
            val respLen = ((b1 and 0xFF) shl 8) or (b2 and 0xFF)
            if (respLen <= 0 || respLen > 4096) return null

            // Read response bytes
            val answer = ByteArray(respLen)
            var readTotal = 0
            while (readTotal < respLen) {
                val n = input.read(answer, readTotal, respLen - readTotal)
                if (n == -1) break
                readTotal += n
            }
            if (readTotal == respLen) answer else null
        } catch (_: Exception) {
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
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
        dnsCache.clear()
        try { tunIn.close() } catch (_: Exception) {}
        try { tunOut.close() } catch (_: Exception) {}
    }
}
