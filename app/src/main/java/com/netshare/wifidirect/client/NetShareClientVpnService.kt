package com.netshare.wifidirect.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.netshare.wifidirect.MainActivity
import com.netshare.wifidirect.R
import com.netshare.wifidirect.model.TrafficMetrics
import com.netshare.wifidirect.util.FormatUtils
import hev.htproxy.TProxyService
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NetShareClientVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CLIENT -> {
                stopClient()
                return START_NOT_STICKY
            }

            ACTION_START_CLIENT, null -> {
                val host = intent?.getStringExtra(EXTRA_GATEWAY_HOST) ?: "192.168.49.1"
                val port = intent?.getIntExtra(EXTRA_GATEWAY_PORT, 8282) ?: 8282
                startClient(host, port)
            }
        }
        return START_STICKY
    }

    private fun startClient(host: String, port: Int) {
        _clientState.update {
            it.copy(
                status = ClientConnectionStatus.CONNECTING,
                gatewayHost = host,
                gatewayPort = port,
                errorMessage = null
            )
        }
        log("Starting native SOCKS5 tunnel to $host:$port...")

        val notification = buildNotification("Connecting to NetShare Hotspot...", "Gateway: $host:$port")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val builder = Builder()
                .setBlocking(false)
                .setMtu(1500)
                .addAddress("10.254.1.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setSession("NetShare Direct Client")

            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }

            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            builder.setConfigureIntent(
                PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )

            val pfd = builder.establish()
            if (pfd == null) {
                setError("Could not establish VPN interface. Make sure VPN permission was granted.")
                stopSelf()
                return
            }

            vpnInterface = pfd

            // Generate tproxy.conf for hev-socks5-tunnel
            val configFile = File(cacheDir, "tproxy.conf")
            val confContent = """
                misc:
                  task-stack-size: 20480
                tunnel:
                  mtu: 1500
                  icmp: 'reply'
                socks5:
                  port: $port
                  address: '$host'
                  udp: 'tcp'
                mapdns:
                  address: 8.8.8.8
                  port: 53
                  network: 240.0.0.0
                  netmask: 240.0.0.0
                  cache-size: 10000
            """.trimIndent()
            configFile.writeText(confContent)

            // Start native hev-socks5-tunnel (lwIP C TCP/IP stack)
            TProxyService.TProxyStartService(configFile.absolutePath, pfd.fd)

            _clientState.update {
                it.copy(
                    status = ClientConnectionStatus.CONNECTED,
                    isGatewayReachable = true,
                    errorMessage = null
                )
            }
            log("Native SOCKS5 Tunnel active! All device apps routing through $host:$port")

            startTrafficMonitor(host, port)
        } catch (e: Exception) {
            setError("Failed to start VPN tunnel: ${e.message}")
            stopClient()
        }
    }

    private fun startTrafficMonitor(host: String, port: Int) {
        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            var lastRx = 0L
            var lastTx = 0L
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                delay(1000L)
                val stats = try { TProxyService.TProxyGetStats() } catch (_: Exception) { null }
                val now = System.currentTimeMillis()
                val elapsed = maxOf(1L, now - lastTime)

                val curRx = stats?.getOrNull(0) ?: lastRx
                val curTx = stats?.getOrNull(1) ?: lastTx

                val downSpeed = ((curRx - lastRx) * 1000L) / elapsed
                val upSpeed = ((curTx - lastTx) * 1000L) / elapsed

                lastRx = curRx
                lastTx = curTx
                lastTime = now

                val metrics = TrafficMetrics(
                    downloadSpeedBytesPerSec = maxOf(0L, downSpeed),
                    uploadSpeedBytesPerSec = maxOf(0L, upSpeed),
                    totalDownloadedBytes = curRx,
                    totalUploadedBytes = curTx
                )

                _clientState.update { it.copy(traffic = metrics) }

                // Update notification text
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val downStr = FormatUtils.formatSpeed(downSpeed)
                val upStr = FormatUtils.formatSpeed(upSpeed)
                val title = "NetShare Client Active"
                val text = "↓ $downStr  ↑ $upStr • Gateway $host:$port"
                nm.notify(NOTIFICATION_ID, buildNotification(title, text))
            }
        }
    }

    private fun stopClient() {
        log("Stopping NetShare Client VPN...")
        monitorJob?.cancel()
        monitorJob = null

        try {
            TProxyService.TProxyStopService()
        } catch (_: Exception) {
        }

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null

        _clientState.update {
            it.copy(
                status = ClientConnectionStatus.DISCONNECTED,
                traffic = TrafficMetrics()
            )
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setError(msg: String) {
        log("ERROR: $msg")
        _clientState.update {
            it.copy(
                status = ClientConnectionStatus.ERROR,
                errorMessage = msg
            )
        }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$time] $msg"
        _clientState.update { current ->
            val updated = (current.logs + entry).takeLast(100)
            current.copy(logs = updated)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, NetShareClientVpnService::class.java).apply {
            action = ACTION_STOP_CLIENT
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi_direct)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", pendingStop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NetShare Client Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live status and bandwidth for NetShare Client Mode"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopClient()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "netshare_client_vpn_channel"
        const val NOTIFICATION_ID = 2048

        const val ACTION_START_CLIENT = "com.netshare.wifidirect.action.START_CLIENT"
        const val ACTION_STOP_CLIENT = "com.netshare.wifidirect.action.STOP_CLIENT"

        const val EXTRA_GATEWAY_HOST = "extra_gateway_host"
        const val EXTRA_GATEWAY_PORT = "extra_gateway_port"

        private val _clientState = MutableStateFlow(ClientUiState())
        val clientState: StateFlow<ClientUiState> = _clientState.asStateFlow()

        fun start(context: Context, host: String = "192.168.49.1", port: Int = 8282) {
            val intent = Intent(context, NetShareClientVpnService::class.java).apply {
                action = ACTION_START_CLIENT
                putExtra(EXTRA_GATEWAY_HOST, host)
                putExtra(EXTRA_GATEWAY_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NetShareClientVpnService::class.java).apply {
                action = ACTION_STOP_CLIENT
            }
            context.startService(intent)
        }

        fun clearLogs() {
            _clientState.update { it.copy(logs = emptyList()) }
        }
    }
}
