package com.netshare.wifidirect.routing

import android.net.TrafficStats
import com.netshare.wifidirect.model.TrafficMetrics
import java.io.File

/**
 * Reads interface byte counters from `/proc/net/dev` or sysfs `/sys/class/net/<iface>/statistics/`
 * for the active Wi-Fi Direct P2P interface (`p2p-wlan0-0` / `p2p0`).
 */
class TrafficMonitor {

    private var baseRxBytes: Long = -1L
    private var baseTxBytes: Long = -1L
    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L
    private var lastTimestampMs: Long = 0L

    fun reset() {
        baseRxBytes = -1L
        baseTxBytes = -1L
        lastRxBytes = -1L
        lastTxBytes = -1L
        lastTimestampMs = 0L
    }

    fun sample(p2pInterfaceName: String): TrafficMetrics {
        val now = System.currentTimeMillis()
        val counters = readInterfaceCounters(p2pInterfaceName)
        val currentRx = counters.first
        val currentTx = counters.second

        if (baseRxBytes < 0L || currentRx < baseRxBytes) {
            baseRxBytes = currentRx
            baseTxBytes = currentTx
            lastRxBytes = currentRx
            lastTxBytes = currentTx
            lastTimestampMs = now
            return TrafficMetrics()
        }

        val elapsedMs = (now - lastTimestampMs).coerceAtLeast(1L)
        val deltaRx = (currentRx - lastRxBytes).coerceAtLeast(0L)
        val deltaTx = (currentTx - lastTxBytes).coerceAtLeast(0L)

        // Note: On the Group Owner interface (p2p-*), TX bytes sent out of p2p-* are downloaded by clients,
        // and RX bytes received on p2p-* are uploaded by clients.
        val downloadSpeed = (deltaTx * 1000L) / elapsedMs
        val uploadSpeed = (deltaRx * 1000L) / elapsedMs

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTimestampMs = now

        return TrafficMetrics(
            downloadSpeedBytesPerSec = downloadSpeed,
            uploadSpeedBytesPerSec = uploadSpeed,
            totalDownloadedBytes = (currentTx - baseTxBytes).coerceAtLeast(0L),
            totalUploadedBytes = (currentRx - baseRxBytes).coerceAtLeast(0L)
        )
    }

    private fun readInterfaceCounters(iface: String): Pair<Long, Long> {
        val cleanIface = iface.trim()
        if (cleanIface.isNotEmpty()) {
            // 1. Try sysfs statistics for the specific P2P interface
            try {
                val rxFile = File("/sys/class/net/$cleanIface/statistics/rx_bytes")
                val txFile = File("/sys/class/net/$cleanIface/statistics/tx_bytes")
                if (rxFile.canRead() && txFile.canRead()) {
                    val rx = rxFile.readText().trim().toLongOrNull()
                    val tx = txFile.readText().trim().toLongOrNull()
                    if (rx != null && tx != null) {
                        return rx to tx
                    }
                }
            } catch (_: Exception) {
            }

            // 2. Try /proc/net/dev
            try {
                val devFile = File("/proc/net/dev")
                if (devFile.canRead()) {
                    devFile.useLines { lines ->
                        lines.forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.startsWith("$cleanIface:")) {
                                val afterColon = trimmed.substringAfter(":").trim()
                                val cols = afterColon.split(Regex("\\s+"))
                                if (cols.size >= 9) {
                                    val rx = cols[0].toLongOrNull() ?: 0L
                                    val tx = cols[8].toLongOrNull() ?: 0L
                                    return rx to tx
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Fallback to total device TrafficStats
        val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        return rx to tx
    }
}

