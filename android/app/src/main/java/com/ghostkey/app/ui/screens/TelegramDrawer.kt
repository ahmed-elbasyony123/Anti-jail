package com.ghostkey.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.net.ArtiTorState
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.ui.*

/**
 * Modern Telegram-style Navigation Drawer (Sidebar) with Anthropic warm branding.
 */
@Composable
fun TelegramDrawer(
    userAlias: String,
    myPgpKeys: PgpKeyPairArmored?,
    relayStatus: RelayStatus,
    artiState: ArtiTorState,
    onNavigateChats: () -> Unit,
    onNavigateSettings: () -> Unit,
    onCopyDropId: () -> Unit,
    onCopyFingerprint: () -> Unit,
    onCopyPublicKey: () -> Unit = {},
    onEmergencyPanic: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = AnthropicSurface,
        drawerContentColor = AnthropicCream,
        modifier = Modifier.width(300.dp)
    ) {
        // ---------------------------------------------------------------------
        // Drawer Header: User Profile & Identity
        // ---------------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AnthropicSurfaceVariant)
                .padding(horizontal = 18.dp, vertical = 24.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(AnthropicTerracotta),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (userAlias.ifBlank { "G" }).take(1).uppercase(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = AnthropicCream
                        )
                    }

                    // Tor connection indicator badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (relayStatus == RelayStatus.CONNECTED) AnthropicSage.copy(alpha = 0.2f) else AnthropicDarkNeutral)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (relayStatus == RelayStatus.CONNECTED) AnthropicSage else AnthropicTextMuted)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (relayStatus == RelayStatus.CONNECTED) "TOR ACTIVE" else "CONNECTING",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (relayStatus == RelayStatus.CONNECTED) AnthropicSage else AnthropicTextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = userAlias.ifBlank { "Ghost (Anonymous)" },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = AnthropicCream
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Drop ID row with copy button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onCopyDropId() }
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = if (myPgpKeys != null) "Drop ID: ${myPgpKeys.dropId.take(10)}..." else "No identity",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AnthropicTextMuted
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy Drop ID",
                        tint = AnthropicTerracotta,
                        modifier = Modifier.size(13.dp)
                    )
                }

                // Fingerprint row with copy button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onCopyFingerprint() }
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = if (myPgpKeys != null) "PGP: ${myPgpKeys.fingerprint.take(8)}..." else "PGP: Not ready",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AnthropicTextMuted
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Default.VpnKey,
                        contentDescription = "Copy Fingerprint",
                        tint = AnthropicSage,
                        modifier = Modifier.size(13.dp)
                    )
                }

                // Copy Full Public Key row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onCopyPublicKey() }
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = if (myPgpKeys != null) "Copy Full Public Key" else "Key: Not ready",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = AnthropicTextLight
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Key,
                        contentDescription = "Copy Full Public Key",
                        tint = AnthropicBlue,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ---------------------------------------------------------------------
        // Drawer Menu Items
        // ---------------------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DrawerItem(
                icon = Icons.Default.Chat,
                title = "All Chats & Contacts",
                badge = null,
                onClick = onNavigateChats
            )

            DrawerItem(
                icon = Icons.Default.Public,
                title = "Tor & Arti Network",
                badge = when (artiState) {
                    is ArtiTorState.Connected -> "Arti Live 🧅"
                    is ArtiTorState.Starting -> if (artiState.progress > 0) "Arti: ${artiState.progress}%" else "Booting..."
                    else -> "Ready"
                },
                onClick = onNavigateSettings
            )

            DrawerItem(
                icon = Icons.Default.Settings,
                title = "Settings (الإعدادات)",
                badge = null,
                onClick = onNavigateSettings
            )

            HorizontalDivider(
                color = AnthropicBorderSubtle,
                modifier = Modifier.padding(vertical = 10.dp)
            )

            DrawerItem(
                icon = Icons.Default.Shield,
                title = "Zero-Metadata Security",
                badge = "Verified",
                onClick = onNavigateSettings
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // ---------------------------------------------------------------------
        // Drawer Footer: Emergency Panic Wipe
        // ---------------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Button(
                onClick = onEmergencyPanic,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AnthropicCrimson.copy(alpha = 0.2f),
                    contentColor = AnthropicCrimson
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("PANIC WIPE (تدمير ذاتي)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    title: String,
    badge: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = AnthropicTextMid,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            color = AnthropicCream,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (badge != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AnthropicSurfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badge,
                    color = AnthropicTerracotta,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
