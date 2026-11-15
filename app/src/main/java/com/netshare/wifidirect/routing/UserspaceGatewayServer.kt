package com.netshare.wifidirect.routing

import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * High-performance Userspace Gateway (HTTP Proxy + SOCKS5 + Captive Portal Responder + PAC Server)
 * bound to `0.0.0.0:8282` and `192.168.49.1:8282` for non-rooted Android internet sharing.
 *
 * Capabilities:
 * 1. HTTP CONNECT tunneling (HTTPS traffic for all apps & browsers).
 * 2. Standard HTTP proxy forwarding with transparent re-writing.
 * 3. RFC 1928 SOCKS5 proxy server for VPN clients (Super Proxy, Every Proxy, V2Ray, tun2socks).
 * 4. Captive portal 204 handler (returns 204 No Content to Google/Android probes so Android marks Wi-Fi as connected).
 * 5. Diagnostic web dashboard at `http://192.168.49.1:8282/` for instant reachability testing from client devices.
 * 6. Dual-path upstream socket routing: binds to active Android [Network] if available, with automatic
 *    fallback to the system default socket route if binding fails.
 */
class UserspaceGatewayServer(
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null
    private var serverSocket: ServerSocket? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start(
        groupOwnerIp: String = "192.168.49.1",
        port: Int = GATEWAY_PORT,
        upstreamNetworkProvider: () -> Network?
    ): Boolean {
        stop()
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            // Bind to wildcard 0.0.0.0 so both 192.168.49.1 and loopback are always accessible
            server.bind(InetSocketAddress("0.0.0.0", port), 256)
            serverSocket = server
            isRunning = true
            onLog("Userspace Gateway active on port $port (Test page: http://$groupOwnerIp:$port/)")

            acceptJob = scope.launch {
                while (isActive && !server.isClosed) {
                    try {
                        val client = server.accept()
                        client.tcpNoDelay = true
                        launch {
                            handleClientConnection(client, groupOwnerIp, port, upstreamNetworkProvider)
                        }
                    } catch (_: Exception) {
                        if (server.isClosed) break
                    }
                }
            }
            true
        } catch (e: Exception) {
            onLog("Userspace Gateway bind error on port $port: ${e.message}")
            isRunning = false
            false
        }
    }

    private fun handleClientConnection(
        client: Socket,
        groupOwnerIp: String,
        port: Int,
        upstreamNetworkProvider: () -> Network?
    ) {
        try {
            // Security ACL: Only allow connections from trusted Wi-Fi Direct subnet or loopback
            val remoteAddr = client.inetAddress
            val isLoopback = remoteAddr.isLoopbackAddress || remoteAddr.hostAddress == "127.0.0.1"
            val subnetPrefix = if (groupOwnerIp.contains(".")) {
                groupOwnerIp.substringBeforeLast(".") + "."
            } else {
                "192.168.49."
            }
            val isAllowedSubnet = remoteAddr.hostAddress?.startsWith(subnetPrefix) == true ||
                remoteAddr.hostAddress?.startsWith("192.168.49.") == true

            if (!isLoopback && !isAllowedSubnet) {
                // Reject unauthorized connection from external or upstream network
                client.close()
                return
            }

            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()

            input.mark(4)
            val firstByte = input.read()
            if (firstByte == -1) {
                client.close()
                return
            }
            input.reset()

            // Detect SOCKS5 (first byte == 0x05) vs HTTP/HTTPS
            if (firstByte == 0x05) {
                handleSocks5(client, input, output, upstreamNetworkProvider)
            } else {
                handleHttpOrConnect(client, input, output, groupOwnerIp, port, upstreamNetworkProvider)
            }
        } catch (_: Exception) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleHttpOrConnect(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        groupOwnerIp: String,
        port: Int,
        upstreamNetworkProvider: () -> Network?
    ) {
        val headerBytes = readHttpHeaderBytes(input) ?: run {
            client.close()
            return
        }
        val headerText = String(headerBytes, Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull() ?: run {
            client.close()
            return
        }
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            client.close()
            return
        }

        val method = parts[0].uppercase()
        val rawTarget = parts[1]

        // 1. Android & Apple Captive Portal 204 handler
        if (rawTarget.contains("generate_204") || rawTarget.contains("gen_204") || rawTarget.endsWith("/hotspot-detect.html")) {
            val resp204 = "HTTP/1.1 204 No Content\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache\r\n\r\n"
            output.write(resp204.toByteArray(Charsets.ISO_8859_1))
            output.flush()
            client.close()
            return
        }

        // 2. PAC (Proxy Auto-Configuration) script
        if (method == "GET" && (rawTarget == "/pac" || rawTarget == "/wpad.dat" || rawTarget.endsWith("/pac"))) {
            val pacScript = """
                function FindProxyForURL(url, host) {
                    if (isPlainHostName(host) ||
                        shExpMatch(host, "*.local") ||
                        isInNet(dnsResolve(host), "192.168.49.0", "255.255.255.0") ||
                        isInNet(dnsResolve(host), "127.0.0.0", "255.0.0.0")) {
                        return "DIRECT";
                    }
                    return "PROXY $groupOwnerIp:$port; SOCKS5 $groupOwnerIp:$port; DIRECT";
                }
            """.trimIndent()
            val bodyBytes = pacScript.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-ns-proxy-autoconfig\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(response.toByteArray(Charsets.ISO_8859_1))
            output.write(bodyBytes)
            output.flush()
            client.close()
            return
        }

        // 3. Diagnostic HTML Dashboard if user visits gateway directly in browser
        val hostHeader = lines.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: ""
        val hostOnly = hostHeader.substringBefore(":")
        val isSelfRequest = (rawTarget == "/" || rawTarget == "/index.html" || rawTarget == "/status") &&
            (hostOnly.isEmpty() || hostOnly == groupOwnerIp || hostOnly == "127.0.0.1" || hostOnly == "localhost")

        if (isSelfRequest) {
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>NetShare Direct Gateway</title>
                    <style>
                        body { font-family: -apple-system, sans-serif; background: #0b1120; color: #f1f5f9; padding: 24px; text-align: center; }
                        .card { max-width: 480px; margin: 0 auto; background: #1e293b; border-radius: 16px; padding: 24px; box-shadow: 0 4px 20px rgba(0,0,0,0.4); text-align: left; }
                        h1 { color: #38bdf8; font-size: 22px; margin-top: 0; }
                        .badge { display: inline-block; background: #10b981; color: #042f2e; padding: 4px 10px; border-radius: 99px; font-weight: bold; font-size: 13px; }
                        code { background: #0f172a; padding: 2px 6px; border-radius: 6px; font-family: monospace; color: #38bdf8; }
                        .box { background: #0f172a; border-radius: 10px; padding: 12px; margin: 12px 0; font-size: 14px; line-height: 1.5; }
                        a { color: #38bdf8; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <span class="badge">&#10003; Gateway Connected</span>
                        <h1>NetShare Direct Gateway</h1>
                        <p>This phone is successfully communicating with the Wi-Fi Direct Hotspot host.</p>
                        <div class="box">
                            <b>Gateway IP:</b> <code>$groupOwnerIp</code><br>
                            <b>Proxy Port:</b> <code>$port</code> (HTTP & SOCKS5)<br>
                            <b>PAC Script:</b> <a href="/pac">http://$groupOwnerIp:$port/pac</a>
                        </div>
                        <h3>How to get internet on all apps:</h3>
                        <p style="font-size:14px; color:#cbd5e1;">
                            1. Install <b>Super Proxy</b> or <b>Every Proxy</b> (free from Google Play).<br>
                            2. Add profile: Server <code>$groupOwnerIp</code>, Port <code>$port</code>.<br>
                            3. Tap <b>Start</b> & accept VPN permission. All apps & games will have full internet!
                        </p>
                    </div>
                </body>
                </html>
            """.trimIndent()
            val bodyBytes = html.toByteArray(Charsets.UTF_8)
            val resp = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(resp.toByteArray(Charsets.ISO_8859_1))
            output.write(bodyBytes)
            output.flush()
            client.close()
            return
        }

        // 4. HTTP CONNECT Tunneling (HTTPS)
        if (method == "CONNECT") {
            val hostPort = rawTarget.split(":")
            val targetHost = hostPort[0]
            val targetPort = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

            val remote = connectUpstream(targetHost, targetPort, upstreamNetworkProvider)
            if (remote == null) {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
                client.close()
                return
            }

            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()
            bridgeStreams(client, input, output, remote, remote.getInputStream(), remote.getOutputStream())
        } else {
            // 5. Plain HTTP Request Forwarding
            var targetHost = ""
            var targetPort = 80
            var path = rawTarget

            if (rawTarget.startsWith("http://", ignoreCase = true)) {
                val withoutScheme = rawTarget.substring(7)
                val slashIdx = withoutScheme.indexOf('/')
                val authority = if (slashIdx >= 0) withoutScheme.substring(0, slashIdx) else withoutScheme
                path = if (slashIdx >= 0) withoutScheme.substring(slashIdx) else "/"
                val hp = authority.split(":")
                targetHost = hp[0]
                targetPort = hp.getOrNull(1)?.toIntOrNull() ?: 80
            } else {
                val hp = hostHeader.split(":")
                targetHost = hp[0]
                targetPort = hp.getOrNull(1)?.toIntOrNull() ?: 80
            }

            if (targetHost.isEmpty()) {
                client.close()
                return
            }

            val remote = connectUpstream(targetHost, targetPort, upstreamNetworkProvider)
            if (remote == null) {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
                client.close()
                return
            }

            val rebuiltRequest = StringBuilder()
            rebuiltRequest.append("$method $path ${parts.getOrNull(2) ?: "HTTP/1.1"}\r\n")
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) continue
                if (line.startsWith("Proxy-Connection:", ignoreCase = true)) continue
                rebuiltRequest.append(line).append("\r\n")
            }
            rebuiltRequest.append("\r\n")

            val remoteOut = remote.getOutputStream()
            remoteOut.write(rebuiltRequest.toString().toByteArray(Charsets.ISO_8859_1))
            remoteOut.flush()

            bridgeStreams(client, input, output, remote, remote.getInputStream(), remoteOut)
        }
    }

    private fun handleSocks5(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        upstreamNetworkProvider: () -> Network?
    ) {
        val ver = input.read()
        val nMethods = input.read()
        if (ver != 5 || nMethods <= 0) {
            client.close()
            return
        }
        val methods = readExactBytes(input, nMethods) ?: run {
            client.close()
            return
        }

        // Method reply: 0x05 (SOCKS5), 0x00 (NO AUTH REQUIRED)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val reqVer = input.read()
        val cmd = input.read()
        input.read() // RSV
        val atyp = input.read()
        if (reqVer != 5 || cmd != 1) { // 1 = CONNECT
            if (reqVer == 5) {
                // Command not supported (0x07) according to RFC 1928
                try {
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                } catch (_: Exception) {
                }
            }
            client.close()
            return
        }

        val targetHost = when (atyp) {
            1 -> { // IPv4
                val addr = readExactBytes(input, 4) ?: return
                InetAddress.getByAddress(addr).hostAddress ?: ""
            }
            3 -> { // Domain name
                val len = input.read()
                if (len <= 0) return
                val domainBytes = readExactBytes(input, len) ?: return
                String(domainBytes, Charsets.UTF_8)
            }
            4 -> { // IPv6
                val addr = readExactBytes(input, 16) ?: return
                InetAddress.getByAddress(addr).hostAddress ?: ""
            }
            else -> ""
        }

        val portHi = input.read()
        val portLo = input.read()
        if (portHi == -1 || portLo == -1 || targetHost.isEmpty()) {
            client.close()
            return
        }
        val targetPort = ((portHi and 0xFF) shl 8) or (portLo and 0xFF)

        val remote = connectUpstream(targetHost, targetPort, upstreamNetworkProvider)
        if (remote == null) {
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.close()
            return
        }

        // Success reply
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
        bridgeStreams(client, input, output, remote, remote.getInputStream(), remote.getOutputStream())
    }

    /**
     * Connects to [host]:[port] with automatic dual-routing:
     * 1. Attempts binding to active Android upstream [Network] (Wi-Fi or Mobile Data).
     * 2. If network-bound socket fails, immediately falls back to system default routing.
     */
    private fun connectUpstream(
        host: String,
        port: Int,
        upstreamNetworkProvider: () -> Network?
    ): Socket? {
        val upstreamNetwork = upstreamNetworkProvider()

        // Attempt 1: Via explicit upstream network
        if (upstreamNetwork != null) {
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.soTimeout = 30_000
                upstreamNetwork.bindSocket(socket)
                val resolved = try {
                    upstreamNetwork.getAllByName(host).firstOrNull()
                } catch (_: Exception) {
                    InetAddress.getByName(host)
                } ?: InetAddress.getByName(host)

                socket.connect(InetSocketAddress(resolved, port), 10_000)
                return socket
            } catch (e: Exception) {
                // If binding fails, proceed to fallback
            }
        }

        // Attempt 2: Fallback to system default socket routing
        return try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val resolved = InetAddress.getByName(host)
            socket.connect(InetSocketAddress(resolved, port), 10_000)
            socket
        } catch (e: Exception) {
            onLog("Cannot reach $host:$port (${e.message})")
            null
        }
    }

    private fun bridgeStreams(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        remote: Socket,
        remoteIn: InputStream,
        remoteOut: OutputStream
    ) {
        var clientErrored = false
        val upJob = scope.launch {
            try {
                val buf = ByteArray(32 * 1024)
                while (isActive) {
                    val n = clientIn.read(buf)
                    if (n <= 0) break
                    remoteOut.write(buf, 0, n)
                    remoteOut.flush()
                }
            } catch (_: Exception) {
                clientErrored = true
            } finally {
                try {
                    remote.shutdownOutput()
                } catch (_: Exception) {
                }
                if (clientErrored) {
                    try {
                        remote.close()
                    } catch (_: Exception) {
                    }
                    try {
                        client.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        try {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = remoteIn.read(buf)
                if (n <= 0) break
                clientOut.write(buf, 0, n)
                clientOut.flush()
            }
        } catch (_: Exception) {
        } finally {
            upJob.cancel()
            try {
                client.close()
            } catch (_: Exception) {
            }
            try {
                remote.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray? {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val count = input.read(buffer, totalRead, length - totalRead)
            if (count == -1) return null
            totalRead += count
        }
        return buffer
    }

    private fun readHttpHeaderBytes(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(1024)
        var state = 0
        while (buffer.size() < 16_384) {
            val b = input.read()
            if (b == -1) return if (buffer.size() > 0) buffer.toByteArray() else null
            buffer.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) {
                return buffer.toByteArray()
            }
        }
        return buffer.toByteArray()
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        isRunning = false
    }

    companion object {
        const val GATEWAY_PORT = 8282
    }
}
