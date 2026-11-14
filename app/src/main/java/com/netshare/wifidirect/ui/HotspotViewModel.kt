package com.netshare.wifidirect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.netshare.wifidirect.NetShareApp
import com.netshare.wifidirect.model.HotspotBand
import com.netshare.wifidirect.model.HotspotStatus
import com.netshare.wifidirect.model.HotspotUiState
import com.netshare.wifidirect.model.NatRoutingMode
import com.netshare.wifidirect.model.SpeedLimitPreset
import com.netshare.wifidirect.service.WifiDirectHotspotService
import kotlinx.coroutines.flow.StateFlow

class HotspotViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NetShareApp
    private val wifiDirectManager = app.wifiDirectManager

    val uiState: StateFlow<HotspotUiState> = wifiDirectManager.state

    fun toggleHotspot() {
        val currentStatus = uiState.value.status
        if (currentStatus == HotspotStatus.RUNNING || currentStatus == HotspotStatus.STARTING) {
            WifiDirectHotspotService.stop(app)
        } else {
            WifiDirectHotspotService.start(app)
        }
    }

    fun updateSsidSuffix(suffix: String) {
        val clean = suffix.replace("\n", "").take(22)
        val current = uiState.value.config
        wifiDirectManager.updateConfig(current.copy(customSsidSuffix = clean))
    }

    fun updatePassphrase(passphrase: String) {
        val clean = passphrase.replace("\n", "").take(63)
        val current = uiState.value.config
        wifiDirectManager.updateConfig(current.copy(customPassphrase = clean))
    }

    fun updateBand(band: HotspotBand) {
        val current = uiState.value.config
        wifiDirectManager.updateConfig(current.copy(band = band))
    }

    fun updateSpeedLimit(speedLimit: SpeedLimitPreset) {
        val current = uiState.value.config
        wifiDirectManager.updateConfig(current.copy(speedLimit = speedLimit))
    }

    fun updateAutoDhcp(enabled: Boolean) {
        val current = uiState.value.config
        wifiDirectManager.updateConfig(current.copy(autoDhcpServer = enabled))
    }

    fun updatePersistentGroup(enabled: Boolean) {
        val current = uiState.value.config
        wifiDirectManager.updateConfig(current.copy(persistentGroup = enabled))
    }

    fun updateRoutingMode(mode: NatRoutingMode) {
        val current = uiState.value.config
        wifiDirectManager.updateConfig(current.copy(routingMode = mode))
    }

    fun refreshClients() {
        wifiDirectManager.refreshGroupAndConnectionInfo()
    }

    fun clearLogs() {
        wifiDirectManager.clearLogs()
    }
}
