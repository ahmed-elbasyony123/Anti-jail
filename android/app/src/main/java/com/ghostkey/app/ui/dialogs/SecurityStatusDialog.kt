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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.net.ArtiTorState
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.ui.*

/**
 * Security & Network Health Diagnostics Dialog.
 */
@Composable
fun SecurityStatusDialog(
    relayStatus: RelayStatus,
    artiState: ArtiTorState,
    lanPeersCount: Int,
    isEphemeral: Boolean,
    hasDuressPin: Boolean,
    myFingerprint: String,
    myDropId: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = AppIcons.VerifiedUser,
                contentDescription = null,
                tint = AnthropicTerracotta,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Security & Network Health",
                style = MaterialTheme.typography.titleMedium,
                color = AnthropicCream
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                DiagnosticsCategory(title = "Network & Routing") {
                    val (torLabel, torColor) = relayStatus.statusDisplay()
                    DiagnosticsRow(
                        icon = AppIcons.Public,
                        title = "Tor Circuit",
                        value = torLabel,
                        valueColor = torColor
                    )

                    val artiDetails = when (artiState) {
                        is ArtiTorState.Connected -> "Embedded Arti Rust Engine (SOCKS5 :${artiState.socksPort})"
                        is ArtiTorState.Starting -> "Bootstrapping Circuit (${artiState.progress}%)..."
                        is ArtiTorState.Error -> "Arti Error (Fallback to External Tor)"
                        is ArtiTorState.Idle -> "Tor SOCKS Proxy Ready"
                    }
                    DiagnosticsRow(
                        icon = AppIcons.Public,
                        title = "Tor Engine",
                        value = artiDetails,
                        valueColor = AnthropicTextMuted
                    )

                    DiagnosticsRow(
                        icon = AppIcons.Wifi,
                        title = "Offline LAN Mesh",
                        value = if (lanPeersCount > 0) "$lanPeersCount peer(s) nearby on LAN" else "Active (Listening :8766)",
                        valueColor = if (lanPeersCount > 0) AnthropicSage else AnthropicTextMuted
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                DiagnosticsCategory(title = "Device & Anti-Forensics") {
                    DiagnosticsRow(
                        icon = Icons.Default.Lock,
                        title = "Screen Capture Lock",
                        value = "FLAG_SECURE Active (Zero Screenshots)",
                        valueColor = AnthropicSage
                    )
                    DiagnosticsRow(
                        icon = AppIcons.Memory,
                        title = "Storage Mode",
                        value = if (isEphemeral) "Ephemeral (Zero Disk Storage)" else "RAM-Only Messages (Encrypted Identity)",
                        valueColor = AnthropicSage
                    )
                    DiagnosticsRow(
                        icon = AppIcons.Shield,
                        title = "Duress Panic Wipe",
                        value = if (hasDuressPin) "Armed & Ready (Silent Wipe)" else "Not Configured",
                        valueColor = if (hasDuressPin) AnthropicSage else AnthropicTextMuted
                    )
                    DiagnosticsRow(
                        icon = AppIcons.Hourglass,
                        title = "Disappearing Reaper",
                        value = "Active (Periodic RAM zeroization)",
                        valueColor = AnthropicSage
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                DiagnosticsCategory(title = "Cryptography & Identity") {
                    DiagnosticsRow(
                        icon = AppIcons.Key,
                        title = "PGP Fingerprint",
                        value = if (myFingerprint.isNotBlank()) myFingerprint.take(16) + "..." else "Not Generated",
                        valueColor = AnthropicTerracotta
                    )
                    DiagnosticsRow(
                        icon = Icons.Default.Email,
                        title = "Drop ID",
                        value = if (myDropId.isNotBlank()) myDropId.take(16) + "..." else "None",
                        valueColor = AnthropicTextMuted
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AnthropicTerracotta,
                    contentColor = AnthropicSurface
                )
            ) {
                Text("Close")
            }
        },
        containerColor = AnthropicSurface
    )
}

@Composable
private fun DiagnosticsCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AnthropicSurfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = AnthropicTerracotta,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun DiagnosticsRow(
    icon: ImageVector,
    title: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AnthropicTextMuted,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = AnthropicCream,
                fontSize = 12.sp
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor,
                fontSize = 11.sp
            )
        }
    }
}
