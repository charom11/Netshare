package com.netshare.wifidirect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.netshare.wifidirect.MainActivity
import com.netshare.wifidirect.NetShareApp
import com.netshare.wifidirect.R
import com.netshare.wifidirect.model.HotspotStatus
import com.netshare.wifidirect.model.HotspotUiState
import com.netshare.wifidirect.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service that keeps the Wi-Fi Direct Group Owner and Native Routing alive
 * when the app is in the background or the screen is turned off.
 */
class WifiDirectHotspotService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateCollectorJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as NetShareApp).wifiDirectManager

        when (intent?.action) {
            ACTION_STOP_HOTSPOT -> {
                manager.stopHotspot()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_HOTSPOT, null -> {
                val initialNotification = buildNotification(manager.state.value)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        initialNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, initialNotification)
                }

                acquireLocks()
                if (manager.state.value.status == HotspotStatus.STOPPED ||
                    manager.state.value.status == HotspotStatus.ERROR
                ) {
                    manager.startHotspot()
                }
                observeHotspotState(manager)
            }
        }

        return START_STICKY
    }

    private fun observeHotspotState(manager: com.netshare.wifidirect.p2p.WifiDirectManager) {
        if (stateCollectorJob != null) return
        stateCollectorJob = serviceScope.launch {
            manager.state.collect { uiState ->
                val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (uiState.status == HotspotStatus.RUNNING || uiState.status == HotspotStatus.STARTING) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(uiState))
                } else if (uiState.status == HotspotStatus.STOPPED) {
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "NetShareDirect::HotspotWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L) // up to 12 hours
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockType, "NetShareDirect::HotspotWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(state: HotspotUiState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WifiDirectHotspotService::class.java).apply {
            action = ACTION_STOP_HOTSPOT
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ssidTitle = if (state.activeSsid.isNotEmpty()) {
            "Hotspot Active: ${state.activeSsid}"
        } else {
            "Starting Wi-Fi Direct Hotspot..."
        }

        val peersCount = state.connectedClients.size
        val downSpeed = FormatUtils.formatSpeed(state.traffic.downloadSpeedBytesPerSec)
        val upSpeed = FormatUtils.formatSpeed(state.traffic.uploadSpeedBytesPerSec)
        val bodyText = "$peersCount connected • ↓ $downSpeed  ↑ $upSpeed • ${state.groupOwnerIp}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi_direct)
            .setContentTitle(ssidTitle)
            .setContentText(bodyText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Hotspot",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "wifi_direct_hotspot_channel"
        const val NOTIFICATION_ID = 1049
        const val ACTION_START_HOTSPOT = "com.netshare.wifidirect.action.START_HOTSPOT"
        const val ACTION_STOP_HOTSPOT = "com.netshare.wifidirect.action.STOP_HOTSPOT"

        fun start(context: Context) {
            val intent = Intent(context, WifiDirectHotspotService::class.java).apply {
                action = ACTION_START_HOTSPOT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WifiDirectHotspotService::class.java).apply {
                action = ACTION_STOP_HOTSPOT
            }
            context.startService(intent)
        }
    }
}

