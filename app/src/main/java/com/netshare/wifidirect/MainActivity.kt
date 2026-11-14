package com.netshare.wifidirect

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
                    onToggleHotspot = {
                        if (PermissionHelper.hasWifiDirectPermissions(this)) {
                            viewModel.toggleHotspot()
                        } else {
                            permissionLauncher.launch(
                                PermissionHelper.requiredPermissions().toTypedArray()
                            )
                        }
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
