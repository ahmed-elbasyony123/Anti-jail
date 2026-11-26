package com.ghostkey.app.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.AppLanguage
import com.ghostkey.app.AppThemeMode
import com.ghostkey.app.BuildConfig
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.net.ArtiTorManager
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.net.mesh.MeshPermissions
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.components.*
import com.ghostkey.app.ui.icons.ExtraIcons
import com.ghostkey.app.ui.theme.GhostTheme
import com.ghostkey.app.util.GhostKeyUtils
import java.util.Date

@Composable
fun SettingsScreen(
    initialRelayUrl: String,
    initialUseTorSocks: Boolean,
    initialKeepAlive: Boolean,
    initialEphemeralMode: Boolean,
    initialLockEnabled: Boolean,
    userAlias: String,
    myPgpKeys: PgpKeyPairArmored?,
    appPinConfigured: Boolean,
    duressPinConfigured: Boolean,
    offlineBleMeshEnabled: Boolean,
    offlineWifiDirectEnabled: Boolean,
    language: AppLanguage,
    themeMode: AppThemeMode,
    relayStatus: RelayStatus,
    relayErrorDetail: String?,
    onLanguageChange: (AppLanguage) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onSaveSettings: (
        relayUrl: String,
        useTorSocks: Boolean,
        keepAlive: Boolean,
        ephemeralMode: Boolean,
        lockEnabled: Boolean,
        alias: String
    ) -> Unit,
    onAutoSave: (relayUrl: String, alias: String) -> Unit,
    onSetAppPin: (String?) -> Unit,
    onSetDuressPin: (String?) -> Unit,
    onCopyKey: () -> Unit,
    onGenerateNewKey: () -> Unit,
    onExportBackup: (String) -> Result<String>,
    onImportBackup: (String, String) -> Result<Unit>,
    onToggleBleMesh: (Boolean) -> Unit,
    onToggleWifiDirect: (Boolean) -> Unit,
    onOpenOfflineShare: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val context = LocalContext.current

    var relayUrl by remember { mutableStateOf(initialRelayUrl) }
    var useTorSocks by remember { mutableStateOf(initialUseTorSocks) }
    var keepAlive by remember { mutableStateOf(initialKeepAlive) }
    var ephemeralMode by remember { mutableStateOf(initialEphemeralMode) }
    var lockEnabled by remember { mutableStateOf(initialLockEnabled) }
    var alias by remember { mutableStateOf(userAlias) }

    LaunchedEffect(initialRelayUrl) { if (relayUrl.isBlank() && initialRelayUrl.isNotBlank()) relayUrl = initialRelayUrl }
    LaunchedEffect(userAlias) { alias = userAlias }
    LaunchedEffect(initialEphemeralMode) { ephemeralMode = initialEphemeralMode }
    LaunchedEffect(initialUseTorSocks) { useTorSocks = initialUseTorSocks }
    LaunchedEffect(initialKeepAlive) { keepAlive = initialKeepAlive }
    LaunchedEffect(initialLockEnabled) { lockEnabled = initialLockEnabled }

    var showAppPinDialog by remember { mutableStateOf(false) }
    var showDuressPinDialog by remember { mutableStateOf(false) }
    var showExportBackupDialog by remember { mutableStateOf(false) }
    var showImportBackupDialog by remember { mutableStateOf(false) }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            onToggleBleMesh(true)
        } else {
            Toast.makeText(context, "Bluetooth permissions required for BLE Mesh", Toast.LENGTH_SHORT).show()
        }
    }

    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            onToggleWifiDirect(true)
        } else {
            Toast.makeText(context, "Nearby Wi-Fi permission required for Wi-Fi Direct", Toast.LENGTH_SHORT).show()
        }
    }

    fun commitSettings(showToast: Boolean) {
        val effectiveTor = useTorSocks || relayUrl.contains(".onion", ignoreCase = true)
        onSaveSettings(relayUrl, effectiveTor, keepAlive, ephemeralMode, lockEnabled, alias)
        if (showToast) {
            Toast.makeText(context, strings.saveAndConnectBtn, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .padding(bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = strings.settingsTitle,
            color = palette.textPrimary,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )

        // Appearance & Language
        SettingsSectionCard(title = strings.appearanceSection) {
            Text(strings.languageTitle, color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            SegmentedPillToggle(
                options = listOf(AppLanguage.EN to "English", AppLanguage.AR to "العربية"),
                selected = language,
                onSelect = onLanguageChange
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(strings.themeTitle, color = palette.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            SegmentedPillToggle(
                options = listOf(
                    AppThemeMode.LIGHT to strings.themeLight,
                    AppThemeMode.DARK to strings.themeDark,
                    AppThemeMode.SYSTEM to strings.themeSystem
                ),
                selected = themeMode,
                onSelect = onThemeModeChange
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Tor Network & Relay
        SettingsSectionCard(title = strings.networkSection) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = relayUrl,
                    onValueChange = {
                        relayUrl = it.trim()
                        if (relayUrl.contains(".onion", ignoreCase = true)) useTorSocks = true
                        onAutoSave(relayUrl, alias)
                    },
                    label = { Text(strings.relayUrlLabel) },
                    placeholder = { Text(strings.relayUrlPlaceholder, color = palette.textTertiary) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    trailingIcon = {
                        if (relayUrl.isNotEmpty()) {
                            IconButton(onClick = {
                                relayUrl = ""
                                onAutoSave("", alias)
                            }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = palette.textSecondary)
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val pasted = GhostKeyUtils.readClipboardText(context)?.trim().orEmpty()
                        if (pasted.isNotBlank()) {
                            relayUrl = pasted
                            if (relayUrl.contains(".onion", ignoreCase = true)) useTorSocks = true
                            onAutoSave(relayUrl, alias)
                            Toast.makeText(context, "Pasted", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .background(palette.surfaceVariant, RoundedCornerShape(12.dp))
                ) {
                    Icon(AppIcons.ContentPaste, contentDescription = "Paste", tint = palette.accent)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            SettingsToggleRow(
                icon = AppIcons.Public,
                title = strings.routeViaTorTitle,
                subtitle = strings.routeViaTorSubtitle,
                checked = useTorSocks,
                onCheckedChange = { useTorSocks = it },
                iconTint = palette.accent
            )
            Spacer(modifier = Modifier.height(10.dp))
            PrimaryActionButton(
                text = strings.saveAndConnectBtn,
                onClick = { commitSettings(showToast = true) }
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    val diag = buildDiagnosticsReport(relayUrl, relayStatus, relayErrorDetail, useTorSocks, ephemeralMode)
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("GhostKey Diagnostics", diag))
                    Toast.makeText(context, "Copied diagnostics report", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(AppIcons.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(strings.copyDiagnosticsBtn, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Background & Stealth
        SettingsSectionCard(title = strings.notificationsSection) {
            SettingsToggleRow(
                icon = Icons.Default.Notifications,
                title = strings.keepTorAliveTitle,
                subtitle = strings.keepTorAliveSubtitle,
                checked = keepAlive,
                onCheckedChange = {
                    keepAlive = it
                    commitSettings(showToast = false)
                },
                iconTint = palette.accent
            )
            SettingsDivider()
            SettingsActionRow(
                icon = AppIcons.VisibilityOff,
                title = strings.lockScreenStealthTitle,
                subtitle = strings.lockScreenStealthSubtitle,
                onClick = {}
            )
        }

        // Privacy & Lock
        SettingsSectionCard(title = strings.privacySection) {
            SettingsToggleRow(
                icon = AppIcons.Fingerprint,
                title = strings.deviceLockTitle,
                subtitle = strings.deviceLockSubtitle,
                checked = lockEnabled,
                onCheckedChange = {
                    lockEnabled = it
                    commitSettings(showToast = false)
                },
                iconTint = palette.connected
            )
            SettingsDivider()
            SettingsToggleRow(
                icon = AppIcons.Memory,
                title = strings.ephemeralToggleTitle,
                subtitle = strings.ephemeralToggleSubtitle,
                checked = ephemeralMode,
                onCheckedChange = {
                    ephemeralMode = it
                    commitSettings(showToast = false)
                }
            )
            SettingsDivider()
            SettingsActionRow(
                icon = Icons.Default.Lock,
                title = strings.appPinTitle,
                subtitle = if (appPinConfigured) strings.appPinActiveSubtitle else strings.appPinDisabledSubtitle,
                onClick = { showAppPinDialog = true },
                iconTint = if (appPinConfigured) palette.connected else palette.textPrimary
            )
            SettingsDivider()
            SettingsActionRow(
                icon = Icons.Default.Warning,
                title = strings.duressPinTitle,
                subtitle = if (duressPinConfigured) strings.duressPinActiveSubtitle else strings.duressPinDisabledSubtitle,
                onClick = { showDuressPinDialog = true },
                iconTint = palette.error
            )
        }

        // Cryptography & Backup
        SettingsSectionCard(title = strings.cryptoBackupSection) {
            SettingsActionRow(
                icon = AppIcons.Key,
                title = strings.exportPublicKeyTitle,
                subtitle = strings.exportPublicKeySubtitle,
                onClick = onCopyKey
            )
            SettingsDivider()
            SettingsActionRow(
                icon = ExtraIcons.Upload,
                title = strings.exportBackupTitle,
                subtitle = strings.exportBackupSubtitle,
                onClick = { showExportBackupDialog = true },
                iconTint = palette.accent
            )
            SettingsDivider()
            SettingsActionRow(
                icon = ExtraIcons.Download,
                title = strings.importBackupTitle,
                subtitle = strings.importBackupSubtitle,
                onClick = { showImportBackupDialog = true }
            )
            SettingsDivider()
            SettingsActionRow(
                icon = Icons.Default.Refresh,
                title = strings.generateNewIdentityTitle,
                subtitle = strings.generateNewIdentitySubtitle,
                onClick = onGenerateNewKey,
                iconTint = palette.error,
                titleColor = palette.error
            )
        }

        // Offline Mesh (Opt-in with runtime permission checks)
        SettingsSectionCard(title = strings.offlineMeshSection) {
            SettingsToggleRow(
                icon = ExtraIcons.Bluetooth,
                title = strings.bleMeshTitle,
                subtitle = strings.bleMeshSubtitle,
                checked = offlineBleMeshEnabled,
                onCheckedChange = { enable ->
                    if (!enable) {
                        onToggleBleMesh(false)
                    } else if (MeshPermissions.hasBlePermissions(context)) {
                        onToggleBleMesh(true)
                    } else {
                        blePermissionLauncher.launch(MeshPermissions.requiredBlePermissions().toTypedArray())
                    }
                },
                iconTint = palette.accent
            )
            SettingsDivider()
            SettingsToggleRow(
                icon = AppIcons.Wifi,
                title = strings.wifiDirectTitle,
                subtitle = strings.wifiDirectSubtitle,
                checked = offlineWifiDirectEnabled,
                onCheckedChange = { enable ->
                    if (!enable) {
                        onToggleWifiDirect(false)
                    } else if (MeshPermissions.hasWifiDirectPermissions(context)) {
                        onToggleWifiDirect(true)
                    } else {
                        wifiPermissionLauncher.launch(MeshPermissions.requiredWifiDirectPermissions().toTypedArray())
                    }
                },
                iconTint = palette.accent
            )
            SettingsDivider()
            SettingsActionRow(
                icon = AppIcons.QrCode,
                title = strings.offlineShareTitle,
                subtitle = strings.offlineShareSubtitle,
                onClick = onOpenOfflineShare,
                iconTint = palette.connected
            )
        }
    }

    if (showAppPinDialog) {
        KeypadPinSetupDialog(
            title = strings.appPinTitle,
            isConfigured = appPinConfigured,
            onSavePin = { pin ->
                onSetAppPin(pin)
                showAppPinDialog = false
            },
            onDisablePin = {
                onSetAppPin(null)
                showAppPinDialog = false
            },
            onDismiss = { showAppPinDialog = false }
        )
    }

    if (showDuressPinDialog) {
        KeypadPinSetupDialog(
            title = strings.duressPinTitle,
            isConfigured = duressPinConfigured,
            isDestructive = true,
            onSavePin = { pin ->
                onSetDuressPin(pin)
                showDuressPinDialog = false
            },
            onDisablePin = {
                onSetDuressPin(null)
                showDuressPinDialog = false
            },
            onDismiss = { showDuressPinDialog = false }
        )
    }

    if (showExportBackupDialog) {
        BackupExportDialog(
            onExport = onExportBackup,
            onDismiss = { showExportBackupDialog = false }
        )
    }

    if (showImportBackupDialog) {
        BackupImportDialog(
            onImport = onImportBackup,
            onDismiss = { showImportBackupDialog = false }
        )
    }
}

@Composable
private fun KeypadPinSetupDialog(
    title: String,
    isConfigured: Boolean,
    isDestructive: Boolean = false,
    onSavePin: (String) -> Unit,
    onDisablePin: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    var pin by remember { mutableStateOf("") }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var isSubmitted by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = {
            Text(
                text = if (firstPin == null) title else strings.pinConfirmTitle,
                color = if (isDestructive) palette.error else palette.textPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PinCodeBoxes(pinLength = 6, enteredLength = pin.length, isError = isError)
                if (isError) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(strings.pinMismatchError, color = palette.error, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(14.dp))
                InAppNumericKeypad(
                    keySize = 54.dp,
                    onDigit = { d ->
                        if (!isSubmitted && pin.length < 6) {
                            isError = false
                            val next = pin + d
                            pin = next
                            if (next.length == 6) {
                                val first = firstPin
                                if (first == null) {
                                    firstPin = next
                                    pin = ""
                                } else if (first == next) {
                                    isSubmitted = true
                                    onSavePin(next)
                                } else {
                                    isError = true
                                    firstPin = null
                                    pin = ""
                                }
                            }
                        }
                    },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    onClear = { pin = ""; isError = false }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancelBtn, color = palette.textSecondary)
            }
        },
        dismissButton = {
            if (isConfigured) {
                TextButton(onClick = onDisablePin) {
                    Text("Disable", color = palette.error)
                }
            }
        }
    )
}

@Composable
private fun BackupExportDialog(
    onExport: (String) -> Result<String>,
    onDismiss: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val context = LocalContext.current
    var passphrase by remember { mutableStateOf("") }
    var exportedJson by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text(strings.exportBackupTitle, color = palette.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (exportedJson == null) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it; error = null },
                        label = { Text("Backup Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) Text(error!!, color = palette.error, fontSize = 12.sp)
                } else {
                    Text(
                        text = exportedJson!!.take(220) + "...",
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            if (exportedJson == null) {
                Button(
                    onClick = {
                        if (passphrase.length < 6) {
                            error = "Use at least 6 characters"
                        } else {
                            onExport(passphrase)
                                .onSuccess { exportedJson = it }
                                .onFailure { error = it.message ?: "Export failed" }
                        }
                    }
                ) { Text("Encrypt") }
            } else {
                Button(
                    onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("GhostKey Encrypted Backup", exportedJson))
                        Toast.makeText(context, "Backup copied", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                ) { Text("Copy") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelBtn, color = palette.textSecondary) }
        }
    )
}

@Composable
private fun BackupImportDialog(
    onImport: (String, String) -> Result<Unit>,
    onDismiss: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val context = LocalContext.current
    var backupJson by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text(strings.importBackupTitle, color = palette.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = backupJson,
                    onValueChange = { backupJson = it; error = null },
                    label = { Text("Encrypted Backup JSON") },
                    minLines = 3,
                    maxLines = 5,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        GhostKeyUtils.readClipboardText(context)?.let { backupJson = it }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(AppIcons.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paste Backup")
                }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; error = null },
                    label = { Text("Backup Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) Text(error!!, color = palette.error, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onImport(backupJson, passphrase)
                        .onSuccess {
                            Toast.makeText(context, "Identity restored", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                        .onFailure { error = it.message ?: "Restore failed" }
                }
            ) { Text("Restore") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancelBtn, color = palette.textSecondary) }
        }
    )
}

private fun buildDiagnosticsReport(
    relayUrl: String,
    relayStatus: RelayStatus,
    relayErrorDetail: String?,
    useTorSocks: Boolean,
    ephemeralMode: Boolean
): String = """
    --- GHOSTKEY DIAGNOSTICS ---
    App Version: ${BuildConfig.VERSION_NAME}
    Relay URL: '$relayUrl'
    Relay Status: $relayStatus
    Relay Error: ${relayErrorDetail ?: "none"}
    Use Tor SOCKS: $useTorSocks
    Ephemeral RAM-Only Mode: $ephemeralMode
    Arti Native Loaded: ${ArtiTorManager.isAvailable}
    Arti State: ${ArtiTorManager.state.value}
    Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})
    ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}
    Time: ${Date()}
    ----------------------------
""".trimIndent()
