package com.netshare.wifidirect.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager

/**
 * Listens to system Wi-Fi P2P broadcasts and forwards events to [WifiDirectManager].
 */
class WifiDirectBroadcastReceiver(
    private val onP2pStateChanged: (Boolean) -> Unit,
    private val onConnectionChanged: () -> Unit,
    private val onPeersChanged: () -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(
                    WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED
                )
                onP2pStateChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                onConnectionChanged()
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                onPeersChanged()
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                onConnectionChanged()
            }
        }
    }
}

