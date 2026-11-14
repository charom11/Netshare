package com.netshare.wifidirect.routing

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Lightweight UDP DNS Forwarder bound to the Wi-Fi Direct Group Owner IP (192.168.49.1).
 * Forwards DNS queries from connected Wi-Fi Direct clients directly through the active
 * upstream Android [Network] using [Network.bindSocket].
 */
class DnsForwarder(
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var listenSocket: DatagramSocket? = null

    @Volatile
    var boundPort: Int = FALLBACK_DNS_PORT
        private set

    fun start(
        groupOwnerIp: String = "192.168.49.1",
        upstreamDnsServer: String = "8.8.8.8",
        upstreamNetworkProvider: () -> Network?
    ): Int {
        stop()

        val socket = try {
            // Try binding directly to port 53 if running with root/CAP_NET_BIND_SERVICE
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(groupOwnerIp), PRIVILEGED_DNS_PORT))
            }.also {
                boundPort = PRIVILEGED_DNS_PORT
                onLog("DNS forwarder bound to $groupOwnerIp:$PRIVILEGED_DNS_PORT")
            }
        } catch (_: Exception) {
            // Bind to unprivileged port 5353 (iptables PREROUTING redirects 53 -> 5353)
            try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(groupOwnerIp), FALLBACK_DNS_PORT))
                }.also {
                    boundPort = FALLBACK_DNS_PORT
                    onLog("DNS forwarder bound to $groupOwnerIp:$FALLBACK_DNS_PORT (upstream $upstreamDnsServer)")
                }
            } catch (e: Exception) {
                onLog("DNS forwarder bind skipped: ${e.message}")
                return -1
            }
        }

        listenSocket = socket
        serverJob = scope.launch {
            val buffer = ByteArray(1500)
            while (isActive && !socket.isClosed) {
                try {
                    val requestPacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(requestPacket)

                    val queryBytes = requestPacket.data.copyOfRange(0, requestPacket.length)
                    val clientAddress = requestPacket.address
                    val clientPort = requestPacket.port

                    launch {
                        forwardDnsQuery(
                            listenSocket = socket,
                            queryBytes = queryBytes,
                            clientAddress = clientAddress,
                            clientPort = clientPort,
                            upstreamDnsServer = upstreamDnsServer,
                            upstreamNetwork = upstreamNetworkProvider()
                        )
                    }
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }
        return boundPort
    }

    private fun forwardDnsQuery(
        listenSocket: DatagramSocket,
        queryBytes: ByteArray,
        clientAddress: InetAddress,
        clientPort: Int,
        upstreamDnsServer: String,
        upstreamNetwork: Network?
    ) {
        var upstreamSocket: DatagramSocket? = null
        try {
            upstreamSocket = DatagramSocket()
            upstreamSocket.soTimeout = 3500
            // Bind the UDP socket directly to the active upstream network (Wi-Fi or Mobile Data)
            upstreamNetwork?.bindSocket(upstreamSocket)

            val dnsAddr = InetAddress.getByName(upstreamDnsServer)
            val outPacket = DatagramPacket(queryBytes, queryBytes.size, dnsAddr, PRIVILEGED_DNS_PORT)
            upstreamSocket.send(outPacket)

            val respBuf = ByteArray(1500)
            val respPacket = DatagramPacket(respBuf, respBuf.size)
            upstreamSocket.receive(respPacket)

            val replyPacket = DatagramPacket(
                respPacket.data,
                respPacket.length,
                clientAddress,
                clientPort
            )
            if (!listenSocket.isClosed) {
                listenSocket.send(replyPacket)
            }
        } catch (_: Exception) {
        } finally {
            upstreamSocket?.close()
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            listenSocket?.close()
        } catch (_: Exception) {
        }
        listenSocket = null
    }

    companion object {
        const val PRIVILEGED_DNS_PORT = 53
        const val FALLBACK_DNS_PORT = 5353
    }
}

