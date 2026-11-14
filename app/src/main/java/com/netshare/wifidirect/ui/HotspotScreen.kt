package com.netshare.wifidirect.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.netshare.wifidirect.BuildConfig
import com.netshare.wifidirect.client.ClientConnectionStatus
import com.netshare.wifidirect.client.ClientUiState
import com.netshare.wifidirect.model.ConnectedClient
import com.netshare.wifidirect.model.HotspotBand
import com.netshare.wifidirect.model.HotspotStatus
import com.netshare.wifidirect.model.HotspotUiState
import com.netshare.wifidirect.model.NatRoutingMode
import com.netshare.wifidirect.model.SpeedLimitPreset
import com.netshare.wifidirect.ui.components.QrCodeSheet
import com.netshare.wifidirect.ui.theme.Amber400
import com.netshare.wifidirect.ui.theme.Emerald400
import com.netshare.wifidirect.ui.theme.Emerald500
import com.netshare.wifidirect.ui.theme.Rose500
import com.netshare.wifidirect.ui.theme.Sky400
import com.netshare.wifidirect.ui.theme.Slate800
import com.netshare.wifidirect.util.FormatUtils
import com.netshare.wifidirect.util.GitHubReleaseInfo
import com.netshare.wifidirect.util.GitHubUpdateChecker
import kotlinx.coroutines.launch

enum class AppMode {
    HOST, CLIENT
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HotspotScreen(
    state: HotspotUiState,
    clientState: ClientUiState,
    onToggleHotspot: () -> Unit,
    onToggleClient: (host: String, port: Int) -> Unit,
    onClearClientLogs: () -> Unit,
    onUpdateSsidSuffix: (String) -> Unit,
    onUpdatePassphrase: (String) -> Unit,
    onUpdateBand: (HotspotBand) -> Unit,
    onUpdateSpeedLimit: (SpeedLimitPreset) -> Unit,
    onUpdateAutoDhcp: (Boolean) -> Unit,
    onUpdatePersistent: (Boolean) -> Unit,
    onUpdateRoutingMode: (NatRoutingMode) -> Unit,
    onRefreshClients: () -> Unit,
    onClearLogs: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedMode by rememberSaveable { mutableStateOf(AppMode.HOST) }
    var showQrSheet by rememberSaveable { mutableStateOf(false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(true) }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var isCheckingUpdate by rememberSaveable { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<Result<GitHubReleaseInfo>?>(null) }
    var repoOwnerInput by rememberSaveable { mutableStateOf(GitHubUpdateChecker.repositoryOwner) }
    var repoNameInput by rememberSaveable { mutableStateOf(GitHubUpdateChecker.repositoryName) }
    var repoTokenInput by rememberSaveable { mutableStateOf("") }

    val isRunning = state.status == HotspotStatus.RUNNING
    val isBusy = state.status == HotspotStatus.STARTING || state.status == HotspotStatus.STOPPING

    if (showQrSheet) {
        val ssid = state.activeSsid.ifEmpty { state.config.fullSsidPreview }
        val pass = state.activePassphrase.ifEmpty { state.config.customPassphrase }
        QrCodeSheet(
            ssid = ssid,
            passphrase = pass,
            groupOwnerIp = state.groupOwnerIp,
            onDismiss = { showQrSheet = false }
        )
    }

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = Sky400
                    )
                    Text("GitHub Updates")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Current App Version: v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = repoOwnerInput,
                            onValueChange = { repoOwnerInput = it.trim() },
                            modifier = Modifier.weight(1f),
                            label = { Text("Owner") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = repoNameInput,
                            onValueChange = { repoNameInput = it.trim() },
                            modifier = Modifier.weight(1f),
                            label = { Text("Repository") },
                            singleLine = true
                        )
                    }

                    OutlinedTextField(
                        value = repoTokenInput,
                        onValueChange = { repoTokenInput = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("GitHub Token (Only if repo is Private)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )

                    if (isCheckingUpdate) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Sky400)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Checking GitHub releases...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (updateResult != null) {
                        val res = updateResult!!
                        if (res.isSuccess) {
                            val info = res.getOrThrow()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Slate800.copy(alpha = 0.7f))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (info.hasUpdate) "🎉 New version available: v${info.tagName}" else "✓ You are up to date! (Latest: v${info.tagName})",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (info.hasUpdate) Emerald400 else Sky400
                                )
                                Text(
                                    text = info.releaseNotes.take(160),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Status: ${res.exceptionOrNull()?.message}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Rose500
                                )
                                OutlinedButton(
                                    onClick = {
                                        val rawUrl = "https://github.com/$repoOwnerInput/$repoNameInput/raw/main/NetShareDirect.apk"
                                        GitHubUpdateChecker.openDownload(context, rawUrl)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Download NetShareDirect.apk directly")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val res = updateResult?.getOrNull()
                if (res != null && res.hasUpdate && res.downloadUrl.isNotEmpty()) {
                    Button(
                        onClick = {
                            GitHubUpdateChecker.openDownload(context, res.downloadUrl)
                            showUpdateDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Emerald500)
                    ) {
                        Text("Download Update")
                    }
                } else {
                    Button(
                        onClick = {
                            GitHubUpdateChecker.repositoryOwner = repoOwnerInput
                            GitHubUpdateChecker.repositoryName = repoNameInput
                            isCheckingUpdate = true
                            updateResult = null
                            coroutineScope.launch {
                                updateResult = GitHubUpdateChecker.checkForUpdate(
                                    owner = repoOwnerInput,
                                    repo = repoNameInput,
                                    token = repoTokenInput.ifEmpty { null }
                                )
                                isCheckingUpdate = false
                            }
                        },
                        enabled = !isCheckingUpdate
                    ) {
                        Text("Check Now")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (selectedMode == AppMode.HOST) Icons.Default.WifiTethering else Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = if (selectedMode == AppMode.HOST) {
                                    if (isRunning) Emerald400 else Sky400
                                } else {
                                    if (clientState.status == ClientConnectionStatus.CONNECTED) Emerald400 else Sky400
                                }
                            )
                            Column {
                                Text(
                                    text = "NetShare Direct",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (selectedMode == AppMode.HOST) {
                                        "Host Hotspot • Wi-Fi Direct & Gateway"
                                    } else {
                                        "Client Mode • All-Traffic Direct Tunnel"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            showUpdateDialog = true
                            if (updateResult == null && !isCheckingUpdate) {
                                isCheckingUpdate = true
                                coroutineScope.launch {
                                    updateResult = GitHubUpdateChecker.checkForUpdate(
                                        owner = repoOwnerInput,
                                        repo = repoNameInput,
                                        token = repoTokenInput.ifEmpty { null }
                                    )
                                    isCheckingUpdate = false
                                }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "Check for updates from GitHub"
                            )
                        }
                        if (selectedMode == AppMode.HOST) {
                            IconButton(onClick = onRefreshClients) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh status"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )

                TabRow(
                    selectedTabIndex = selectedMode.ordinal,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = Sky400,
                    divider = {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        )
                    }
                ) {
                    Tab(
                        selected = selectedMode == AppMode.HOST,
                        onClick = { selectedMode = AppMode.HOST },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WifiTethering,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (selectedMode == AppMode.HOST) Sky400 else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Host (Phone 1)",
                                    fontWeight = if (selectedMode == AppMode.HOST) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedMode == AppMode.HOST) Sky400 else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                    Tab(
                        selected = selectedMode == AppMode.CLIENT,
                        onClick = { selectedMode = AppMode.CLIENT },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VpnKey,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if (selectedMode == AppMode.CLIENT) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Client (Phone 2)",
                                    fontWeight = if (selectedMode == AppMode.CLIENT) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedMode == AppMode.CLIENT) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedMode) {
            AppMode.HOST -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
            // 1. Primary Hotspot Status & Control Card
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
                        StatusBadge(state = state)

                        if (state.frequencyMhz > 0) {
                            val bandLabel = if (state.frequencyMhz >= 5000) "5 GHz" else "2.4 GHz"
                            Text(
                                text = "$bandLabel (${state.frequencyMhz} MHz)",
                                style = MaterialTheme.typography.labelMedium,
                                color = Sky400,
                                fontFamily = FontFamily.Monospace
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

                    val displaySsid = if (isRunning && state.activeSsid.isNotEmpty()) {
                        state.activeSsid
                    } else {
                        state.config.fullSsidPreview
                    }
                    val displayPass = if (isRunning && state.activePassphrase.isNotEmpty()) {
                        state.activePassphrase
                    } else {
                        state.config.customPassphrase
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Slate800.copy(alpha = 0.65f))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CredentialRow(
                            label = "Hotspot SSID",
                            value = displaySsid,
                            onCopy = { copyToClipboard(context, "SSID", displaySsid) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "WPA2 Password",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (showPassword) displayPass else "•".repeat(displayPass.length.coerceAtLeast(8)),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle password"
                                    )
                                }
                                IconButton(onClick = { copyToClipboard(context, "Password", displayPass) }) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy password"
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            InfoColumn(
                                label = "Gateway / DNS IP",
                                value = state.groupOwnerIp
                            )
                            InfoColumn(
                                label = "Non-Root Gateway Port",
                                value = "${state.groupOwnerIp}:8282"
                            )
                            InfoColumn(
                                label = "Upstream Internet",
                                value = state.upstreamInfo.transportType
                            )
                        }
                    }

                    // 2nd Phone Connection Helper Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Sky400.copy(alpha = 0.10f))
                            .border(1.dp, Sky400.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "2nd Phone Internet Setup:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Sky400
                            )
                            Text(
                                text = "• If Host Phone is ROOTED: 2nd phone gets full internet automatically.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "• If Host Phone is NOT ROOTED: On 2nd phone -> Wi-Fi settings for $displaySsid -> Proxy -> Manual -> Hostname: ${state.groupOwnerIp}  Port: 8282 (or Auto-Config PAC: http://${state.groupOwnerIp}:8282/pac)",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onToggleHotspot,
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isRunning) Rose500 else Emerald500,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (state.status) {
                                    HotspotStatus.RUNNING -> "Stop Wi-Fi Direct Hotspot"
                                    HotspotStatus.STARTING -> "Starting Group Owner..."
                                    HotspotStatus.STOPPING -> "Stopping..."
                                    else -> "Start Wi-Fi Direct Hotspot"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = { showQrSheet = true },
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCode2,
                                contentDescription = "Show Wi-Fi QR Code"
                            )
                        }
                    }
                }
            }

            // 2. Live Traffic, Native Routing & Built-in DHCP Server Status Card
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
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = Sky400
                            )
                            Text(
                                text = "Live Bandwidth, DHCP & Routing",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = state.config.speedLimit.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = Sky400,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MetricBox(
                            modifier = Modifier.weight(1f),
                            title = "Download (TX to Peers)",
                            speed = FormatUtils.formatSpeed(state.traffic.downloadSpeedBytesPerSec),
                            total = "Total: ${FormatUtils.formatBytes(state.traffic.totalDownloadedBytes)}",
                            accent = Emerald400
                        )
                        MetricBox(
                            modifier = Modifier.weight(1f),
                            title = "Upload (RX from Peers)",
                            speed = FormatUtils.formatSpeed(state.traffic.uploadSpeedBytesPerSec),
                            total = "Total: ${FormatUtils.formatBytes(state.traffic.totalUploadedBytes)}",
                            accent = Sky400
                        )
                    }

                    // Routing Engine Badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Slate800.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Router,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (state.routingActive) Emerald400 else Amber400
                            )
                            Text(
                                text = "Routing:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = state.activeRoutingSummary,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (state.routingActive) Emerald400 else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // DHCP Server Badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Slate800.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (state.dhcpActive) Sky400 else Amber400
                            )
                            Text(
                                text = "DHCP Server:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = state.activeDhcpSummary,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (state.dhcpActive) Sky400 else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 3. Connected Clients Card
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
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                tint = Emerald400
                            )
                            Text(
                                text = "Connected Clients (${state.connectedClients.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "Subnet 192.168.49.0/24",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (state.connectedClients.isEmpty()) {
                        Text(
                            text = if (isRunning) {
                                "No devices connected yet. Connect your 2nd phone to \"${state.activeSsid}\" via Wi-Fi or scan the QR code."
                            } else {
                                "Start the Wi-Fi Direct Hotspot to allow devices to connect."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.connectedClients.forEach { client ->
                            ConnectedClientItem(client = client)
                        }
                    }
                }
            }

            // 4. Hotspot, DHCP & Speed Limit Configuration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SettingsInputAntenna,
                            contentDescription = null,
                            tint = Sky400
                        )
                        Text(
                            text = "Wi-Fi Direct, DHCP & Speed Settings",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (isRunning) {
                        Text(
                            text = "Stop the hotspot to modify SSID, WPA2 passphrase, band, or speed limit.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Amber400
                        )
                    }

                    OutlinedTextField(
                        value = state.config.customSsidSuffix,
                        onValueChange = onUpdateSsidSuffix,
                        enabled = !isRunning,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Hotspot Name (Broadcast as ${state.config.fullSsidPreview})") }
                    )

                    OutlinedTextField(
                        value = state.config.customPassphrase,
                        onValueChange = onUpdatePassphrase,
                        enabled = !isRunning,
                        singleLine = true,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("WPA2 Passphrase (8–63 characters)") }
                    )

                    // Frequency Band Selection
                    Text(
                        text = "Operating Frequency Band (Android 10+)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HotspotBand.entries.forEach { band ->
                            FilterChip(
                                selected = state.config.band == band,
                                onClick = { if (!isRunning) onUpdateBand(band) },
                                enabled = !isRunning,
                                label = { Text(band.label) }
                            )
                        }
                    }

                    // Bandwidth Speed Limit Selection
                    Text(
                        text = "Client Bandwidth Speed Limit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SpeedLimitPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = state.config.speedLimit == preset,
                                onClick = { if (!isRunning) onUpdateSpeedLimit(preset) },
                                enabled = !isRunning,
                                label = { Text(preset.label) }
                            )
                        }
                    }

                    // Automatic DHCP Server Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Built-in Automatic DHCP Server",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Assigns 192.168.49.100–.240 + Gateway 192.168.49.1 (Option 3) & DNS (Option 6)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.config.autoDhcpServer,
                            onCheckedChange = onUpdateAutoDhcp,
                            enabled = !isRunning
                        )
                    }

                    // Native Routing Engine Mode Selection
                    Text(
                        text = "Native Routing Mode (No Proxy / No VPN)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        NatRoutingMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = !isRunning) { onUpdateRoutingMode(mode) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = state.config.routingMode == mode,
                                    onClick = { if (!isRunning) onUpdateRoutingMode(mode) },
                                    enabled = !isRunning
                                )
                                Column(modifier = Modifier.padding(start = 6.dp)) {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = mode.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Persistent Wi-Fi Direct Group",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Remembers group credentials across sessions for faster client reconnect",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.config.persistentGroup,
                            onCheckedChange = onUpdatePersistent,
                            enabled = !isRunning
                        )
                    }
                }
            }

            // 5. Live Diagnostics Log Card
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
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = Sky400
                            )
                            Text(
                                text = "P2P, DHCP & Routing Logs (${state.logs.size})",
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
                                    text = "Ready. Press 'Start Wi-Fi Direct Hotspot' to initialize Group Owner & DHCP Server.",
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
            AppMode.CLIENT -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    ClientScreen(
                        state = clientState,
                        onToggleClient = onToggleClient,
                        onClearLogs = onClearClientLogs
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(state: HotspotUiState) {
    val (dotColor, statusText) = when (state.status) {
        HotspotStatus.RUNNING -> Emerald400 to "HOTSPOT ACTIVE (GROUP OWNER)"
        HotspotStatus.STARTING -> Amber400 to "CREATING P2P GROUP..."
        HotspotStatus.STOPPING -> Amber400 to "STOPPING HOTSPOT..."
        HotspotStatus.ERROR -> Rose500 to "ERROR"
        HotspotStatus.STOPPED -> Color(0xFF64748B) to "STANDBY • READY"
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
private fun CredentialRow(
    label: String,
    value: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy $label"
            )
        }
    }
}

@Composable
private fun InfoColumn(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MetricBox(
    modifier: Modifier = Modifier,
    title: String,
    speed: String,
    total: String,
    accent: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Slate800.copy(alpha = 0.65f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = speed,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = accent
        )
        Text(
            text = total,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConnectedClientItem(client: ConnectedClient) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Slate800.copy(alpha = 0.6f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = client.deviceName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "MAC: ${client.macAddress} • IP: ${client.ipAddress ?: "DHCP assigned"}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = client.statusText,
            style = MaterialTheme.typography.labelSmall,
            color = Emerald400,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
}
