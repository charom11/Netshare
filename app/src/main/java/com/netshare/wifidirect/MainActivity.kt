package com.netshare.wifidirect

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.netshare.wifidirect.client.ClientConnectionStatus
import com.netshare.wifidirect.client.NetShareClientVpnService
import com.netshare.wifidirect.ui.HotspotScreen
import com.netshare.wifidirect.ui.HotspotViewModel
import com.netshare.wifidirect.ui.theme.NetShareTheme
import com.netshare.wifidirect.util.PermissionHelper

class MainActivity : ComponentActivity() {

    private val viewModel: HotspotViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NetShareTheme {
                val state by viewModel.uiState.collectAsState()
                val clientState by NetShareClientVpnService.clientState.collectAsState()

                var pendingHost by remember { mutableStateOf("192.168.49.1") }
                var pendingPort by remember { mutableStateOf(8282) }

                val vpnLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        NetShareClientVpnService.start(this, pendingHost, pendingPort)
                    } else {
                        Toast.makeText(
                            this,
                            "VPN permission was denied. Cannot tunnel client traffic.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    if (PermissionHelper.hasWifiDirectPermissions(this)) {
                        viewModel.toggleHotspot()
                    } else {
                        Toast.makeText(
                            this,
                            "Wi-Fi Direct / Nearby Devices permission is required to start the hotspot.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                HotspotScreen(
                    state = state,
                    clientState = clientState,
                    onToggleHotspot = {
                        if (PermissionHelper.hasWifiDirectPermissions(this)) {
                            viewModel.toggleHotspot()
                        } else {
                            permissionLauncher.launch(
                                PermissionHelper.requiredPermissions().toTypedArray()
                            )
                        }
                    },
                    onToggleClient = { host, port ->
                        if (clientState.status == ClientConnectionStatus.CONNECTED ||
                            clientState.status == ClientConnectionStatus.CONNECTING
                        ) {
                            NetShareClientVpnService.stop(this)
                        } else {
                            pendingHost = host
                            pendingPort = port
                            val prepareIntent = VpnService.prepare(this)
                            if (prepareIntent != null) {
                                vpnLauncher.launch(prepareIntent)
                            } else {
                                NetShareClientVpnService.start(this, host, port)
                            }
                        }
                    },
                    onClearClientLogs = {
                        NetShareClientVpnService.clearLogs()
                    },
                    onUpdateSsidSuffix = viewModel::updateSsidSuffix,
                    onUpdatePassphrase = viewModel::updatePassphrase,
                    onUpdateBand = viewModel::updateBand,
                    onUpdateSpeedLimit = viewModel::updateSpeedLimit,
                    onUpdateAutoDhcp = viewModel::updateAutoDhcp,
                    onUpdatePersistent = viewModel::updatePersistentGroup,
                    onUpdateRoutingMode = viewModel::updateRoutingMode,
                    onRefreshClients = viewModel::refreshClients,
                    onClearLogs = viewModel::clearLogs
                )
            }
        }
    }
}
