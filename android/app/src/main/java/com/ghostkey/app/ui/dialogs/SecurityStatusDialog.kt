package com.ghostkey.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.net.ArtiTorState
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.theme.GhostTheme
import com.ghostkey.app.ui.theme.statusColor
import com.ghostkey.app.ui.theme.statusLabel

@Composable
fun SecurityStatusDialog(
    relayStatus: RelayStatus,
    artiState: ArtiTorState,
    lanPeersCount: Int,
    blePeersCount: Int,
    wifiDirectPeersCount: Int,
    isEphemeral: Boolean,
    hasDuressPin: Boolean,
    myFingerprint: String,
    myDropId: String,
    relayErrorDetail: String? = null,
    onDismiss: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        icon = {
            Icon(AppIcons.VerifiedUser, contentDescription = null, tint = palette.accent, modifier = Modifier.size(30.dp))
        },
        title = {
            Text("Security & Network Health", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DiagnosticsGroup("Network & Mesh") {
                    val label = relayStatus.statusLabel(strings)
                    DiagnosticsRow(
                        icon = AppIcons.Public,
                        title = "Tor Relay",
                        value = if (!relayErrorDetail.isNullOrBlank()) "$label ($relayErrorDetail)" else label,
                        valueColor = relayStatus.statusColor(palette)
                    )
                    val artiDetails = when (artiState) {
                        is ArtiTorState.Connected -> "Embedded Arti (SOCKS5 :${artiState.socksPort})"
                        is ArtiTorState.Starting -> "Bootstrapping Circuit (${artiState.progress}%)"
                        is ArtiTorState.Error -> "Arti Fallback (Orbot)"
                        is ArtiTorState.Idle -> "Tor SOCKS Ready"
                    }
                    DiagnosticsRow(AppIcons.Public, "Tor Engine", artiDetails, palette.textSecondary)
                    DiagnosticsRow(
                        icon = AppIcons.Wifi,
                        title = "Local & Mesh Peers",
                        value = "LAN: $lanPeersCount · BLE: $blePeersCount · Wi-Fi Direct: $wifiDirectPeersCount",
                        valueColor = if (lanPeersCount + blePeersCount + wifiDirectPeersCount > 0) palette.connected else palette.textSecondary
                    )
                }

                DiagnosticsGroup("Anti-Forensics") {
                    DiagnosticsRow(Icons.Default.Lock, "Screen Capture Block", "FLAG_SECURE Active", palette.connected)
                    DiagnosticsRow(
                        AppIcons.Memory,
                        "Storage Mode",
                        if (isEphemeral) "Ephemeral RAM-Only" else "Hardware KeyStore + Volatile RAM Messages",
                        palette.connected
                    )
                    DiagnosticsRow(
                        AppIcons.Shield,
                        "Duress Panic Wipe",
                        if (hasDuressPin) "Armed" else "Not Set",
                        if (hasDuressPin) palette.connected else palette.textSecondary
                    )
                }

                DiagnosticsGroup("Cryptographic Identity") {
                    DiagnosticsRow(
                        AppIcons.Key,
                        strings.fingerprintLabel,
                        if (myFingerprint.isNotBlank()) myFingerprint.take(16) + "..." else "Pending",
                        palette.accent
                    )
                    DiagnosticsRow(
                        Icons.Default.Email,
                        strings.dropIdLabel,
                        if (myDropId.isNotBlank()) myDropId.take(16) + "..." else "Pending",
                        palette.textSecondary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentText)
            ) { Text("Close") }
        }
    )
}

@Composable
private fun DiagnosticsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = GhostTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(title, color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun DiagnosticsRow(icon: ImageVector, title: String, value: String, valueColor: Color) {
    val palette = GhostTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, color = palette.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(value, color = valueColor, fontSize = 11.sp)
        }
    }
}
