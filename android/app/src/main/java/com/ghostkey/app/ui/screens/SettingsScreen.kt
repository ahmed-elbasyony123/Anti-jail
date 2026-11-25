package com.ghostkey.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.BuildConfig
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.net.ArtiTorManager
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.ui.*
import com.ghostkey.app.util.GhostKeyUtils
import java.util.Date

/**
 * Grouped Settings Screen with Anthropic design tokens.
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
    onSetDuressPin: (String?) -> Unit = {},
    appPinConfigured: Boolean = false,
    onSetAppPin: (String?) -> Unit = {},
    onAutoSave: (relayUrl: String, alias: String) -> Unit = { _, _ -> },
    relayStatus: RelayStatus = RelayStatus.DISCONNECTED,
    relayErrorDetail: String? = null
) {
    var relayUrl by remember { mutableStateOf(initialRelayUrl) }
    var useTorSocks by remember { mutableStateOf(initialUseTorSocks) }
    var keepAlive by remember { mutableStateOf(initialKeepAlive) }
    var ephemeralMode by remember { mutableStateOf(initialEphemeralMode) }
    var lockEnabled by remember { mutableStateOf(initialLockEnabled) }
    var alias by remember { mutableStateOf(userAlias) }
    var showDuressDialog by remember { mutableStateOf(false) }
    var duressInput by remember { mutableStateOf("") }
    var showAppPinDialog by remember { mutableStateOf(false) }
    var appPinInput by remember { mutableStateOf("") }
    var appPinConfirmInput by remember { mutableStateOf("") }
    var appPinError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fun commitSettings(showToast: Boolean, navigateBack: Boolean) {
        val effectiveTor = useTorSocks || relayUrl.contains(".onion", ignoreCase = true)
        onSaveSettings(relayUrl, effectiveTor, keepAlive, ephemeralMode, lockEnabled, alias)
        if (showToast) {
            Toast.makeText(context, "Saved! Connecting to relay...", Toast.LENGTH_SHORT).show()
        }
        if (navigateBack) onBack()
    }

    BackHandler {
        commitSettings(showToast = false, navigateBack = true)
    }

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
                    IconButton(onClick = { commitSettings(showToast = false, navigateBack = true) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AnthropicCream
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { commitSettings(showToast = true, navigateBack = true) }) {
                        Text("Save", color = AnthropicTerracotta, fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AnthropicSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(AnthropicBorder))
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
                        onValueChange = {
                            alias = it
                            onAutoSave(relayUrl, alias)
                        },
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

            SettingsGroup(title = "TOR NETWORK & ARTI ENGINE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = relayUrl,
                        onValueChange = {
                            relayUrl = it.trim()
                            if (relayUrl.contains(".onion", ignoreCase = true)) {
                                useTorSocks = true
                            }
                            onAutoSave(relayUrl, alias)
                        },
                        label = { Text("Relay (.onion or IP:Port)") },
                        placeholder = { Text("e.g. xyz...onion:8765") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        trailingIcon = {
                            if (relayUrl.isNotEmpty()) {
                                IconButton(onClick = {
                                    relayUrl = ""
                                    onAutoSave(relayUrl, alias)
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = AnthropicTextMuted)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AnthropicTerracotta,
                            unfocusedBorderColor = AnthropicBorder,
                            focusedTextColor = AnthropicCream,
                            unfocusedTextColor = AnthropicCream
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            val pasted = GhostKeyUtils.readClipboardText(context)?.trim().orEmpty()
                            if (pasted.isNotBlank()) {
                                relayUrl = pasted
                                if (relayUrl.contains(".onion", ignoreCase = true)) {
                                    useTorSocks = true
                                }
                                onAutoSave(relayUrl, alias)
                                Toast.makeText(context, "Pasted from clipboard! (تم اللصق)", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AnthropicSurface)
                            .border(1.dp, AnthropicBorder, RoundedCornerShape(12.dp))
                    ) {
                        Icon(AppIcons.ContentPaste, contentDescription = "Paste", tint = AnthropicTerracotta)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                SettingsToggleRow(
                    icon = AppIcons.Public,
                    iconBg = AnthropicTerracotta,
                    title = "Route Traffic via Tor",
                    subtitle = "Built-in Arti Rust engine with auto-fallback to Orbot",
                    checked = useTorSocks,
                    onCheckedChange = { useTorSocks = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { commitSettings(showToast = true, navigateBack = false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AnthropicTerracotta,
                        contentColor = AnthropicDarkBg
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(AppIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save & Connect (حفظ وإعادة الاتصال)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        val artiAvailable = ArtiTorManager.isAvailable
                        val artiStateStr = ArtiTorManager.state.value.toString()
                        val abis = Build.SUPPORTED_ABIS.joinToString(", ")
                        val diag = """
                            --- GHOSTKEY DIAGNOSTICS ---
                            App Version: ${BuildConfig.VERSION_NAME}
                            Relay URL: '$relayUrl'
                            Relay Status: $relayStatus
                            Relay Error: ${relayErrorDetail ?: "none"}
                            Use Tor SOCKS: $useTorSocks
                            Arti Native Library Loaded: $artiAvailable
                            Arti Engine State: $artiStateStr
                            Device Model: ${Build.MANUFACTURER} ${Build.MODEL}
                            Android SDK: ${Build.VERSION.SDK_INT}
                            Supported ABIs: $abis
                            Time: ${Date()}
                            ----------------------------
                        """.trimIndent()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("GhostKey Diagnostics", diag))
                        Toast.makeText(context, "Diagnostics copied to clipboard! (تم نسخ التقرير)", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AnthropicSlate),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(AppIcons.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Diagnostics (نسخ تقرير تشخيص الاتصال)", fontSize = 12.sp)
                }
            }

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
                        Icon(AppIcons.VisibilityOff, contentDescription = null, tint = AnthropicTextMuted, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Lock Screen Stealth", color = AnthropicCream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("Notification text & sender masked on secure lockscreens (VISIBILITY_PRIVATE)", color = AnthropicTextMuted, fontSize = 11.sp)
                    }
                }
            }

            SettingsGroup(title = "PRIVACY & DEVICE SECURITY") {
                SettingsToggleRow(
                    icon = AppIcons.Fingerprint,
                    iconBg = AnthropicSage,
                    title = "Require Device Lock",
                    subtitle = "Demands device PIN, pattern or biometric fingerprint upon opening",
                    checked = lockEnabled,
                    onCheckedChange = { lockEnabled = it }
                )

                HorizontalDivider(color = AnthropicBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                SettingsToggleRow(
                    icon = AppIcons.Memory,
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
                            appPinInput = ""
                            appPinConfirmInput = ""
                            appPinError = null
                            showAppPinDialog = true
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AnthropicSage.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = AnthropicSage, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("App Lock PIN (رمز قفل التطبيق)", color = AnthropicCream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (appPinConfigured) "Active 🔒: Demands PIN on launch/resume (Tap to change)" else "Disabled: Tap to set an in-app lock PIN",
                            color = if (appPinConfigured) AnthropicSage else AnthropicTextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

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

            SettingsGroup(title = "CRYPTOGRAPHY & PGP KEYS") {
                SettingsActionRow(
                    icon = AppIcons.Key,
                    iconBg = AnthropicTerracotta,
                    title = "Export / Copy Public Key",
                    subtitle = "Copies armored public key with Drop-ID to clipboard (auto-clears)",
                    onClick = onCopyKey
                )

                HorizontalDivider(color = AnthropicBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))

                SettingsActionRow(
                    icon = AppIcons.VerifiedUser,
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
                    subtitle = "Creates fresh Ed25519/X25519 keypair and new Drop ID",
                    onClick = onGenerateNewKey
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        if (showAppPinDialog) {
            AlertDialog(
                onDismissRequest = { showAppPinDialog = false },
                containerColor = AnthropicSurface,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = AnthropicSage)
                        Spacer(Modifier.width(8.dp))
                        Text("App Lock PIN", color = AnthropicCream, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Choose a 4 to 8 digit PIN. Whenever you launch GhostKey or return from the background, this PIN will be required to unlock your session.",
                            color = AnthropicTextMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                        OutlinedTextField(
                            value = appPinInput,
                            onValueChange = {
                                if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                    appPinInput = it
                                    appPinError = null
                                }
                            },
                            label = { Text("Enter PIN (4-8 digits)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AnthropicSage,
                                unfocusedBorderColor = AnthropicBorder,
                                focusedTextColor = AnthropicCream,
                                unfocusedTextColor = AnthropicCream
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = appPinConfirmInput,
                            onValueChange = {
                                if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                    appPinConfirmInput = it
                                    appPinError = null
                                }
                            },
                            label = { Text("Confirm PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AnthropicSage,
                                unfocusedBorderColor = AnthropicBorder,
                                focusedTextColor = AnthropicCream,
                                unfocusedTextColor = AnthropicCream
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (appPinError != null) {
                            Text(
                                text = appPinError ?: "",
                                color = AnthropicCrimson,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (appPinInput.length < 4) {
                                appPinError = "PIN must be at least 4 digits."
                            } else if (appPinInput != appPinConfirmInput) {
                                appPinError = "PINs do not match."
                            } else {
                                onSetAppPin(appPinInput)
                                showAppPinDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AnthropicSage)
                    ) {
                        Text("Save PIN", fontWeight = FontWeight.Bold, color = AnthropicDarkBg)
                    }
                },
                dismissButton = {
                    if (appPinConfigured) {
                        TextButton(onClick = {
                            onSetAppPin(null)
                            showAppPinDialog = false
                        }) {
                            Text("Disable Lock", color = AnthropicCrimson)
                        }
                    } else {
                        TextButton(onClick = { showAppPinDialog = false }) {
                            Text("Cancel", color = AnthropicTextMuted)
                        }
                    }
                }
            )
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
            border = CardDefaults.outlinedCardBorder().copy(brush = SolidColor(AnthropicBorder))
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
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AnthropicTextMuted)
    }
}
