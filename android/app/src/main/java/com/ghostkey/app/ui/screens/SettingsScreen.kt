package com.ghostkey.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.ui.*

/**
 * Dedicated Modern Telegram-style Grouped Settings Screen with Anthropic design tokens.
 *
 * Logically organized into:
 * 1. 👤 Profile & Identity (Pseudonym / Display Name, Drop ID, Fingerprint)
 * 2. 🧅 Tor & Network Routing (Relay server, Built-in Arti Rust engine)
 * 3. 🔔 Notifications & Background Sync (Tor Push Notifications keep-alive)
 * 4. 🔒 Privacy & Device Security (Biometric/PIN screen lock, Ephemeral RAM-only mode)
 * 5. 🔑 Cryptography & Keys (Key generation, Safety numbers verification)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialRelayUrl: String,
    initialUseTorSocks: Boolean,
    initialKeepAlive: Boolean,
    initialEphemeralMode: Boolean,
    initialLockEnabled: Boolean,
    userAlias: String,
    myPgpKeys: PgpKeyPairArmored?,
    onBack: () -> Unit,
    onSaveSettings: (
        relayUrl: String,
        useTorSocks: Boolean,
        keepAlive: Boolean,
        ephemeralMode: Boolean,
        lockEnabled: Boolean,
        alias: String
    ) -> Unit,
    onGenerateNewKey: () -> Unit,
    onCopyKey: () -> Unit,
    onVerifySafetyCode: () -> Unit,
    duressPinConfigured: Boolean = false,
    onSetDuressPin: (String?) -> Unit = {}
) {
    var relayUrl by remember { mutableStateOf(initialRelayUrl) }
    var useTorSocks by remember { mutableStateOf(initialUseTorSocks) }
    var keepAlive by remember { mutableStateOf(initialKeepAlive) }
    var ephemeralMode by remember { mutableStateOf(initialEphemeralMode) }
    var lockEnabled by remember { mutableStateOf(initialLockEnabled) }
    var alias by remember { mutableStateOf(userAlias) }
    var showDuressDialog by remember { mutableStateOf(false) }
    var duressInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = AnthropicCream
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        onSaveSettings(relayUrl, useTorSocks, keepAlive, ephemeralMode, lockEnabled, alias)
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AnthropicCream
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AnthropicSurface,
                    titleContentColor = AnthropicCream
                )
            )
        },
        containerColor = AnthropicDarkBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // -------------------------------------------------------------------------
            // 1. Profile Banner (Telegram Style)
            // -------------------------------------------------------------------------
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AnthropicSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(AnthropicBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(AnthropicTerracotta),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (alias.ifBlank { "G" }).take(1).uppercase(),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = AnthropicCream
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = alias.ifBlank { "Ghost (Anonymous)" },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = AnthropicCream
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (myPgpKeys != null) "Drop ID: ${myPgpKeys.dropId.take(12)}..." else "No Identity In RAM",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = AnthropicTextMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        label = { Text("Display Pseudonym (اسم مستعار)") },
                        placeholder = { Text("e.g. Shadow, Cipher, Alice") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AnthropicTerracotta,
                            unfocusedBorderColor = AnthropicBorder,
                            focusedTextColor = AnthropicCream,
                            unfocusedTextColor = AnthropicCream
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // -------------------------------------------------------------------------
            // 2. Tor & Network Routing
            // -------------------------------------------------------------------------
            SettingsGroup(title = "TOR NETWORK & ARTI ENGINE") {
                OutlinedTextField(
                    value = relayUrl,
                    onValueChange = {
                        relayUrl = it.trim()
                        if (relayUrl.contains(".onion", ignoreCase = true)) {
                            useTorSocks = true
                        }
                    },
                    label = { Text("Relay (.onion or IP:Port)") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AnthropicTerracotta,
                        unfocusedBorderColor = AnthropicBorder,
                        focusedTextColor = AnthropicCream,
                        unfocusedTextColor = AnthropicCream
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                SettingsToggleRow(
                    icon = Icons.Default.Public,
                    iconBg = AnthropicTerracotta,
                    title = "Route Traffic via Tor",
                    subtitle = "Built-in Arti Rust engine with auto-fallback to Orbot",
                    checked = useTorSocks,
                    onCheckedChange = { useTorSocks = it }
                )
            }

            // -------------------------------------------------------------------------
            // 3. Notifications & Background Keep-Alive
            // -------------------------------------------------------------------------
            SettingsGroup(title = "NOTIFICATIONS & TOR PUSH") {
                SettingsToggleRow(
                    icon = Icons.Default.Notifications,
                    iconBg = AnthropicSlate,
                    title = "Keep Tor Active in Background",
                    subtitle = "Receives instant messages over Tor without Google Play Services (Zero FCM)",
                    checked = keepAlive,
                    onCheckedChange = { keepAlive = it }
                )

                HorizontalDivider(color = AnthropicBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AnthropicSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = AnthropicTextMuted, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Lock Screen Stealth", color = AnthropicCream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Notification text & sender masked on secure lockscreens (VISIBILITY_PRIVATE)", color = AnthropicTextMuted, fontSize = 11.sp)
                    }
                }
            }

            // -------------------------------------------------------------------------
            // 4. Privacy & Device Security
            // -------------------------------------------------------------------------
            SettingsGroup(title = "PRIVACY & DEVICE SECURITY") {
                SettingsToggleRow(
                    icon = Icons.Default.Fingerprint,
                    iconBg = AnthropicSage,
                    title = "Require Device Lock",
                    subtitle = "Demands device PIN, pattern or biometric fingerprint upon opening",
                    checked = lockEnabled,
                    onCheckedChange = { lockEnabled = it }
                )

                HorizontalDivider(color = AnthropicBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                SettingsToggleRow(
                    icon = Icons.Default.Memory,
                    iconBg = AnthropicDarkNeutral,
                    title = "Ephemeral RAM-Only Mode",
                    subtitle = "Identity is never saved to Android KeyStore; purged on every exit",
                    checked = ephemeralMode,
                    onCheckedChange = { ephemeralMode = it }
                )

                HorizontalDivider(color = AnthropicBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            duressInput = ""
                            showDuressDialog = true
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AnthropicCrimson.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AnthropicCrimson, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Duress PIN (Silent Panic Wipe)", color = AnthropicCream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (duressPinConfigured) "Configured: Entering decoy PIN silently executes emergency wipe" else "Disabled: Tap to set an emergency decoy wipe PIN",
                            color = if (duressPinConfigured) AnthropicSage else AnthropicTextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // -------------------------------------------------------------------------
            // 5. Cryptography & Keys
            // -------------------------------------------------------------------------
            SettingsGroup(title = "CRYPTOGRAPHY & PGP KEYS") {
                SettingsActionRow(
                    icon = Icons.Default.VpnKey,
                    iconBg = AnthropicTerracotta,
                    title = "Export / Copy Public Key",
                    subtitle = "Copies armored public key with Drop-ID to clipboard (auto-clears)",
                    onClick = onCopyKey
                )

                HorizontalDivider(color = AnthropicBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                SettingsActionRow(
                    icon = Icons.Default.VerifiedUser,
                    iconBg = AnthropicSage,
                    title = "Safety Numbers & Verification",
                    subtitle = "Compare security codes with peer to prevent MITM attacks",
                    onClick = onVerifySafetyCode
                )

                HorizontalDivider(color = AnthropicBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                SettingsActionRow(
                    icon = Icons.Default.Refresh,
                    iconBg = AnthropicCrimson,
                    title = "Generate New Identity",
                    subtitle = "Creates fresh 4096-bit Ed25519/X25519 keypair and new Drop ID",
                    onClick = onGenerateNewKey
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        if (showDuressDialog) {
            AlertDialog(
                onDismissRequest = { showDuressDialog = false },
                containerColor = AnthropicSurface,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AnthropicCrimson)
                        Spacer(Modifier.width(8.dp))
                        Text("Duress PIN", color = AnthropicCream, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Enter a 4 to 6 digit decoy PIN. If you are ever forced to unlock GhostKey under duress, enter this PIN instead: GhostKey will instantly wipe all cryptographic keys, delete local data, and terminate with zero forensic traces.",
                            color = AnthropicTextMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        OutlinedTextField(
                            value = duressInput,
                            onValueChange = { if (it.length <= 6 && it.all { ch -> ch.isDigit() }) duressInput = it },
                            label = { Text("Duress PIN (4-6 digits)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AnthropicCrimson,
                                unfocusedBorderColor = AnthropicBorder,
                                focusedTextColor = AnthropicCream,
                                unfocusedTextColor = AnthropicCream
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (duressInput.length >= 4) {
                                onSetDuressPin(duressInput)
                                showDuressDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AnthropicCrimson)
                    ) {
                        Text("Set Duress PIN", fontWeight = FontWeight.Bold, color = AnthropicCream)
                    }
                },
                dismissButton = {
                    if (duressPinConfigured) {
                        TextButton(onClick = {
                            onSetDuressPin(null)
                            showDuressDialog = false
                        }) {
                            Text("Disable PIN", color = AnthropicTextMuted)
                        }
                    } else {
                        TextButton(onClick = { showDuressDialog = false }) {
                            Text("Cancel", color = AnthropicTextMuted)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = AnthropicTerracotta,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = AnthropicSurface),
            border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(AnthropicBorder))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = AnthropicCream, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AnthropicCream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = AnthropicTextMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AnthropicCream,
                checkedTrackColor = AnthropicTerracotta,
                uncheckedThumbColor = AnthropicTextMuted,
                uncheckedTrackColor = AnthropicBorder
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = AnthropicCream, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AnthropicCream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = AnthropicTextMuted, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AnthropicTextMuted)
    }
}
