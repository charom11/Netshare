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
        onLog("TUN-to-Proxy Engine started (Target Gateway: $gatewayHost:$gatewayPort)")

        engineJob = scope.launch {
            val buffer = ByteArray(32768)
            while (isActive) {
                try {
                    val readLen = tunIn.read(buffer)
                    if (readLen <= 0) break

                    totalRxBytes.addAndGet(readLen.toLong())
                    val ipPacket = PacketUtils.parseIp(buffer, readLen) ?: continue

                    // Ignore loopback or direct traffic to the gateway port itself
                    if (ipPacket.dstIp == gatewayHost && ipPacket.ipPayloadOffset + 4 <= readLen) {
                        val dstPort = ((buffer[ipPacket.ipPayloadOffset + 2].toInt() and 0xFF) shl 8) or
                            (buffer[ipPacket.ipPayloadOffset + 3].toInt() and 0xFF)
                        if (dstPort == gatewayPort) continue
                    }

                    when (ipPacket.protocol) {
                        PacketUtils.PROTOCOL_ICMP -> {
                            val reply = PacketUtils.buildIcmpReply(buffer, readLen)
                            if (reply != null) {
                                synchronized(tunOut) {
                                    tunOut.write(reply)
                                }
                                totalTxBytes.addAndGet(reply.size.toLong())
                            }
                        }

                        PacketUtils.PROTOCOL_UDP -> {
                            val udpPacket = PacketUtils.parseUdp(buffer, ipPacket) ?: continue
                            // Handle DNS queries (port 53)
                            if (udpPacket.dstPort == 53) {
                                val dnsQuery = buffer.copyOfRange(udpPacket.payloadOffset, udpPacket.payloadOffset + udpPacket.payloadLength)
                                launch {
                                    forwardDns(ipPacket, udpPacket, dnsQuery)
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

        // 1. Connection initiation: SYN
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

            // Reply with SYN-ACK immediately
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

            // Connect to NetShare Gateway via HTTP CONNECT
            scope.launch {
                initiateProxyConnection(session, ip.dstIp, tcp.dstPort)
            }
            return
        }

        val session = tcpConnections[key] ?: return

        // 2. Client ACK after SYN-ACK or connection close
        if ((tcp.flags and PacketUtils.TCP_FLAG_RST) != 0 || (tcp.flags and PacketUtils.TCP_FLAG_FIN) != 0) {
            closeSession(session, sendFin = true)
            return
        }

        // 3. Client data payload
        if (tcp.payloadLength > 0) {
            session.clientSeq = (tcp.seqNumber + tcp.payloadLength) and 0xFFFFFFFFL

            // ACK the data packet back to TUN
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

            // Forward payload to the proxy socket
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
        var proxySocket: Socket? = null
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            // Protect socket so its outbound traffic bypasses the VPN tunnel!
            if (!protectSocket(socket)) {
                onLog("Failed to protect outbound proxy socket")
            }

            socket.connect(InetSocketAddress(gatewayHost, gatewayPort), 8000)
            proxySocket = socket
            session.socket = socket

            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            session.socketOut = out

            // Send HTTP CONNECT to gateway
            val connectReq = "CONNECT $targetHost:$targetPort HTTP/1.1\r\nHost: $targetHost:$targetPort\r\n\r\n"
            out.write(connectReq.toByteArray(Charsets.ISO_8859_1))
            out.flush()

            // Read HTTP 200 response
            val respLine = readLine(input)
            if (respLine == null || !respLine.contains("200")) {
                closeSession(session, sendFin = true)
                return
            }
            // Consume remaining headers
            while (true) {
                val line = readLine(input)
                if (line.isNullOrEmpty()) break
            }

            session.isEstablished = true

            // Read data from proxy and inject back into TUN as TCP packets
            val buf = ByteArray(16384)
            while (!session.isClosed && socket.isConnected && !socket.isClosed) {
                val n = input.read(buf)
                if (n <= 0) break

                val chunk = buf.copyOf(n)
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
                session.serverSeq = (session.serverSeq + n) and 0xFFFFFFFFL
                sendToTun(dataPacket)
            }
        } catch (_: Exception) {
        } finally {
            closeSession(session, sendFin = true)
        }
    }

    private fun forwardDns(
        ip: PacketUtils.ParsedIpPacket,
        udp: PacketUtils.ParsedUdpPacket,
        dnsQuery: ByteArray
    ) {
        var udpSocket: DatagramSocket? = null
        try {
            udpSocket = DatagramSocket()
            udpSocket.soTimeout = 4000
            protectDatagram(udpSocket)

            // Send query to Phone 1 DNS forwarder (192.168.49.1:53) or fallback 8.8.8.8
            val targetDns = try {
                InetAddress.getByName(gatewayHost)
            } catch (_: Exception) {
                InetAddress.getByName("8.8.8.8")
            }

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
        } catch (_: Exception) {
        } finally {
            try {
                udpSocket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun sendToTun(packet: ByteArray) {
        try {
            synchronized(tunOut) {
                tunOut.write(packet)
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

        try {
            session.socket?.close()
        } catch (_: Exception) {
        }
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
        try {
            tunIn.close()
        } catch (_: Exception) {
        }
        try {
            tunOut.close()
        } catch (_: Exception) {
        }
    }
}

