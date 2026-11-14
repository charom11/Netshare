package com.netshare.wifidirect.client

import com.netshare.wifidirect.model.TrafficMetrics

enum class ClientConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class ClientUiState(
    val status: ClientConnectionStatus = ClientConnectionStatus.DISCONNECTED,
    val gatewayHost: String = "192.168.49.1",
    val gatewayPort: Int = 8282,
    val isGatewayReachable: Boolean = false,
    val pingLatencyMs: Long = -1L,
    val traffic: TrafficMetrics = TrafficMetrics(),
    val errorMessage: String? = null,
    val logs: List<String> = emptyList()
)
