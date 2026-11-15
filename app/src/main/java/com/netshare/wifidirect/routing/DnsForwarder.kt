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
 * Robust UDP DNS Forwarder bound to 0.0.0.0 on fallback ports (8253, 5353) and privileged port 53 (if root).
 * Forwards queries from connected Wi-Fi Direct clients to upstream DNS servers (active Network or 8.8.8.8 / 1.1.1.1).
 */
class DnsForwarder(
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private var listenSocket: DatagramSocket? = null
    private var auxSocket: DatagramSocket? = null

    @Volatile
    var boundPort: Int = PRIMARY_UNPRIVILEGED_DNS_PORT
        private set

    fun start(
        groupOwnerIp: String = "192.168.49.1",
        upstreamDnsServer: String = "8.8.8.8",
        upstreamNetworkProvider: () -> Network?
    ): Int {
        stop()

        // 1. Try privileged port 53 (if rooted)
        var primarySocket: DatagramSocket? = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", PRIVILEGED_DNS_PORT))
            }.also {
                boundPort = PRIVILEGED_DNS_PORT
                onLog("DNS forwarder active on port $PRIVILEGED_DNS_PORT (Root / Privileged)")
            }
        } catch (_: Exception) {
            null
        }

        // 2. If port 53 not available, bind to PRIMARY_UNPRIVILEGED_DNS_PORT (8253)
        if (primarySocket == null) {
            primarySocket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", PRIMARY_UNPRIVILEGED_DNS_PORT))
                }.also {
                    boundPort = PRIMARY_UNPRIVILEGED_DNS_PORT
                    onLog("DNS forwarder listening on UDP port $PRIMARY_UNPRIVILEGED_DNS_PORT & $FALLBACK_DNS_PORT")
                }
            } catch (e: Exception) {
                onLog("DNS forwarder port $PRIMARY_UNPRIVILEGED_DNS_PORT bind failed: ${e.message}")
                null
            }
        }

        // 3. Also try to bind auxSocket on port 5353 as secondary listener
        try {
            auxSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", FALLBACK_DNS_PORT))
            }
        } catch (_: Exception) {
            auxSocket = null
        }

        if (primarySocket == null && auxSocket == null) {
            onLog("DNS forwarder could not bind any listening port")
            return -1
        }

        listenSocket = primarySocket

        // Start listener loop for primarySocket
        primarySocket?.let { sock ->
            serverJob = scope.launch {
                startListenerLoop(sock, upstreamDnsServer, upstreamNetworkProvider)
            }
        }

        // Start listener loop for auxSocket
        auxSocket?.let { sock ->
            scope.launch {
                startListenerLoop(sock, upstreamDnsServer, upstreamNetworkProvider)
            }
        }

        return boundPort
    }

    private suspend fun startListenerLoop(
        sock: DatagramSocket,
        upstreamDnsServer: String,
        upstreamNetworkProvider: () -> Network?
    ) {
        val buffer = ByteArray(2048)
        while (scope.isActive && !sock.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)

                val queryBytes = packet.data.copyOfRange(0, packet.length)
                val clientAddress = packet.address
                val clientPort = packet.port

                scope.launch {
                    forwardDnsQuery(
                        listenSocket = sock,
                        queryBytes = queryBytes,
                        clientAddress = clientAddress,
                        clientPort = clientPort,
                        upstreamDnsServer = upstreamDnsServer,
                        upstreamNetwork = upstreamNetworkProvider()
                    )
                }
            } catch (_: Exception) {
                if (sock.isClosed) break
            }
        }
    }

    private fun forwardDnsQuery(
        listenSocket: DatagramSocket,
        queryBytes: ByteArray,
        clientAddress: InetAddress,
        clientPort: Int,
        upstreamDnsServer: String,
        upstreamNetwork: Network?
    ) {
        // Security ACL: Only answer DNS queries from trusted Wi-Fi Direct subnet or loopback
        val isLoopback = clientAddress.isLoopbackAddress || clientAddress.hostAddress == "127.0.0.1"
        val isAllowedSubnet = clientAddress.hostAddress?.startsWith("192.168.49.") == true
        if (!isLoopback && !isAllowedSubnet) {
            return
        }

        val upstreamList = listOf(upstreamDnsServer, "8.8.8.8", "1.1.1.1").distinct()

        for (targetServer in upstreamList) {
            var upstreamSocket: DatagramSocket? = null
            try {
                upstreamSocket = DatagramSocket()
                upstreamSocket.soTimeout = 2500

                // Attempt to bind socket to upstream network if available
                if (upstreamNetwork != null) {
                    try {
                        upstreamNetwork.bindSocket(upstreamSocket)
                    } catch (_: Exception) {
                    }
                }

                val dnsAddr = InetAddress.getByName(targetServer)
                val outPacket = DatagramPacket(queryBytes, queryBytes.size, dnsAddr, PRIVILEGED_DNS_PORT)
                upstreamSocket.send(outPacket)

                val respBuf = ByteArray(2048)
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
                return // Success!
            } catch (_: Exception) {
                // Try next upstream server
            } finally {
                try {
                    upstreamSocket?.close()
                } catch (_: Exception) {
                }
            }
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

        try {
            auxSocket?.close()
        } catch (_: Exception) {
        }
        auxSocket = null
    }

    companion object {
        const val PRIVILEGED_DNS_PORT = 53
        const val PRIMARY_UNPRIVILEGED_DNS_PORT = 8253
        const val FALLBACK_DNS_PORT = 5353
    }
}
