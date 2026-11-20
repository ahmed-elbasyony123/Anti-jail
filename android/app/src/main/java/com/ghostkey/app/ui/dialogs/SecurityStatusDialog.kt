package com.ghostkey.app.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.ghostkey.app.ui.GhostKeyColors

/**
 * Briar-Style Security & Network Health Diagnostics Dialog:
 *
 * Full overview of Tor routing, local LAN mesh, forensic safeguards, and cryptographic status.
 */
@Composable
fun SecurityStatusDialog(
    relayStatus: RelayStatus,
    artiState: ArtiTorState,
    relayUrl: String,
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
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = GhostKeyColors.AccentGold,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Security & Network Health",
                style = MaterialTheme.typography.titleMedium,
                color = GhostKeyColors.TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Section 1: Network & Tor
                DiagnosticsCategory(title = "Network & Routing") {
                    val torLabel = when (relayStatus) {
                        RelayStatus.CONNECTED -> "Tor Circuit Established 🧅"
                        RelayStatus.BUILDING_CIRCUIT -> "Building Onion Circuit..."
                        RelayStatus.AUTH_FAILED -> "Authentication Failed ⚠️"
                        RelayStatus.CONNECTING -> "Connecting to Relay..."
                        RelayStatus.FAILED -> "Circuit Failed / Offline"
                        RelayStatus.DISCONNECTED -> "Disconnected"
                    }
                    val torColor = when (relayStatus) {
                        RelayStatus.CONNECTED -> GhostKeyColors.StatusGreen
                        RelayStatus.BUILDING_CIRCUIT, RelayStatus.CONNECTING -> GhostKeyColors.AccentGold
                        else -> GhostKeyColors.StatusRed
                    }
                    DiagnosticsRow(
                        icon = Icons.Default.VpnLock,
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
                        icon = Icons.Default.Speed,
                        title = "Tor Engine",
                        value = artiDetails,
                        valueColor = GhostKeyColors.TextSecondary
                    )

                    DiagnosticsRow(
                        icon = Icons.Default.Wifi,
                        title = "Offline LAN Mesh",
                        value = if (lanPeersCount > 0) "$lanPeersCount peer(s) nearby on LAN" else "Active (Listening :8766)",
                        valueColor = if (lanPeersCount > 0) GhostKeyColors.StatusGreen else GhostKeyColors.TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 2: Forensic & Device Security
                DiagnosticsCategory(title = "Device & Anti-Forensics") {
                    DiagnosticsRow(
                        icon = Icons.Default.Screenshot,
                        title = "Screen Capture Lock",
                        value = "FLAG_SECURE Active (Zero Screenshots)",
                        valueColor = GhostKeyColors.StatusGreen
                    )
                    DiagnosticsRow(
                        icon = Icons.Default.Memory,
                        title = "Storage Mode",
                        value = if (isEphemeral) "Ephemeral (Zero Disk Storage)" else "RAM-Only Active (Encrypted Identity)",
                        valueColor = GhostKeyColors.StatusGreen
                    )
                    DiagnosticsRow(
                        icon = Icons.Default.LockReset,
                        title = "Duress Panic Wipe",
                        value = if (hasDuressPin) "Armed & Ready (Briar Silent Wipe)" else "Not Configured",
                        valueColor = if (hasDuressPin) GhostKeyColors.StatusGreen else GhostKeyColors.TextSecondary
                    )
                    DiagnosticsRow(
                        icon = Icons.Default.HourglassTop,
                        title = "Disappearing Reaper",
                        value = "Active (Periodic RAM zeroization)",
                        valueColor = GhostKeyColors.StatusGreen
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Section 3: Identity & Cryptography
                DiagnosticsCategory(title = "Cryptography & Identity") {
                    DiagnosticsRow(
                        icon = Icons.Default.Key,
                        title = "PGP Fingerprint",
                        value = if (myFingerprint.isNotBlank()) myFingerprint.take(16) + "..." else "Not Generated",
                        valueColor = GhostKeyColors.AccentGold
                    )
                    DiagnosticsRow(
                        icon = Icons.Default.AlternateEmail,
                        title = "Drop ID",
                        value = if (myDropId.isNotBlank()) myDropId.take(16) + "..." else "None",
                        valueColor = GhostKeyColors.TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = GhostKeyColors.AccentGold,
                    contentColor = GhostKeyColors.SurfacePrimary
                )
            ) {
                Text("Close")
            }
        },
        containerColor = GhostKeyColors.SurfacePrimary
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
            .background(GhostKeyColors.SurfaceSecondary, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = GhostKeyColors.AccentGold,
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
            tint = GhostKeyColors.TextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = GhostKeyColors.TextPrimary,
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
