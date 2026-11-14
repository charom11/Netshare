package com.netshare.wifidirect.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netshare.wifidirect.client.ClientConnectionStatus
import com.netshare.wifidirect.client.ClientUiState
import com.netshare.wifidirect.ui.theme.Amber400
import com.netshare.wifidirect.ui.theme.Emerald400
import com.netshare.wifidirect.ui.theme.Emerald500
import com.netshare.wifidirect.ui.theme.Rose500
import com.netshare.wifidirect.ui.theme.Sky400
import com.netshare.wifidirect.ui.theme.Slate800
import com.netshare.wifidirect.util.FormatUtils

@Composable
fun ClientScreen(
    state: ClientUiState,
    onToggleClient: (host: String, port: Int) -> Unit,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    var hostInput by rememberSaveable { mutableStateOf(state.gatewayHost) }
    var portInput by rememberSaveable { mutableStateOf(state.gatewayPort.toString()) }
    var showLogs by rememberSaveable { mutableStateOf(true) }

    val isConnected = state.status == ClientConnectionStatus.CONNECTED
    val isConnecting = state.status == ClientConnectionStatus.CONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Connection Status & Primary Action Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ClientStatusBadge(status = state.status)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = if (isConnected) Emerald400 else Sky400,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "VPN Tunnel Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isConnected) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Rose500.copy(alpha = 0.15f))
                            .border(1.dp, Rose500.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFECDD3)
                        )
                    }
                }

                // Gateway & IP Details
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Slate800.copy(alpha = 0.65f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Target Gateway Host",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${state.gatewayHost}:${state.gatewayPort}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Tunnel IP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isConnected) "10.254.1.2 (Active)" else "—",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = if (isConnected) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Toggle Button
                Button(
                    onClick = {
                        val parsedPort = portInput.toIntOrNull() ?: 8282
                        onToggleClient(hostInput.trim(), parsedPort)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) Rose500 else Emerald500,
                        contentColor = Color.White
                    )
                ) {
                    Icon(imageVector = Icons.Default.PowerSettingsNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (state.status) {
                            ClientConnectionStatus.CONNECTED -> "Disconnect from Hotspot"
                            ClientConnectionStatus.CONNECTING -> "Connecting..."
                            else -> "Connect to NetShare Hotspot"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 2. Live Speed & Bandwidth Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Speed, contentDescription = null, tint = Sky400)
                    Text(
                        text = "Live Bandwidth Received",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Slate800.copy(alpha = 0.65f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Download",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = FormatUtils.formatSpeed(state.traffic.downloadSpeedBytesPerSec),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Emerald400
                        )
                        Text(
                            text = "Total: ${FormatUtils.formatBytes(state.traffic.totalDownloadedBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Slate800.copy(alpha = 0.65f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Upload",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = FormatUtils.formatSpeed(state.traffic.uploadSpeedBytesPerSec),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Sky400
                        )
                        Text(
                            text = "Total: ${FormatUtils.formatBytes(state.traffic.totalUploadedBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 3. Quick Connection Guide Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Wifi, contentDescription = null, tint = Emerald400)
                    Text(
                        text = "How to Connect on this 2nd Phone",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                GuideStep(number = "1", text = "Connect this phone to the Wi-Fi Direct network (e.g. DIRECT-NS-NetShare).")
                GuideStep(number = "2", text = "Tap 'Connect to NetShare Hotspot' above.")
                GuideStep(number = "3", text = "Accept Android's VPN connection prompt (key icon in status bar).")
                GuideStep(number = "✓", text = "All apps, games, YouTube, WhatsApp & browsers now have full internet!")

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Wifi, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Android Wi-Fi Settings")
                }
            }
        }

        // 4. Gateway Settings Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Router, contentDescription = null, tint = Sky400)
                    Text(
                        text = "Hotspot Gateway Settings",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        label = { Text("Gateway IP") },
                        modifier = Modifier.weight(2f),
                        singleLine = true,
                        enabled = !isConnected
                    )

                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it },
                        label = { Text("Port") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !isConnected
                    )
                }
            }
        }

        // 5. Diagnostics Logs Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLogs = !showLogs },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Terminal, contentDescription = null, tint = Sky400)
                        Text(
                            text = "Client Tunnel Logs (${state.logs.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.logs.isNotEmpty()) {
                            TextButton(onClick = onClearLogs) {
                                Text("Clear")
                            }
                        }
                        Icon(
                            imageVector = if (showLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Toggle logs"
                        )
                    }
                }

                AnimatedVisibility(visible = showLogs) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF070B14))
                            .padding(12.dp)
                    ) {
                        if (state.logs.isEmpty()) {
                            Text(
                                text = "Ready. Tap 'Connect to NetShare Hotspot' to start client tunnel.",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                state.logs.takeLast(25).forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (line.contains("ERROR")) Rose500 else Color(0xFFCBD5E1)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun ClientStatusBadge(status: ClientConnectionStatus) {
    val (dotColor, statusText) = when (status) {
        ClientConnectionStatus.CONNECTED -> Emerald400 to "INTERNET ACTIVE"
        ClientConnectionStatus.CONNECTING -> Amber400 to "CONNECTING..."
        ClientConnectionStatus.ERROR -> Rose500 to "ERROR"
        ClientConnectionStatus.DISCONNECTED -> Color(0xFF64748B) to "DISCONNECTED"
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(dotColor.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = dotColor
        )
    }
}

@Composable
private fun GuideStep(number: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Sky400.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Sky400
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

