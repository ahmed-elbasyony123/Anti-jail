package com.ghostkey.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ghostkey.app.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("BatteryLife")
@Composable
fun WelcomeOnboardingScreen(
    onCompleteOnboarding: (alias: String, appPin: String?, duressPin: String?, ephemeralMode: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentStep by remember { mutableIntStateOf(0) }
    var alias by remember { mutableStateOf("") }
    var ephemeralMode by remember { mutableStateOf(false) }

    // Security PINs
    var enablePinLock by remember { mutableStateOf(false) }
    var appPin by remember { mutableStateOf("") }
    var confirmAppPin by remember { mutableStateOf("") }
    var enableDuressPin by remember { mutableStateOf(false) }
    var duressPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    // Key generation animation state
    var isGeneratingKeys by remember { mutableStateOf(false) }
    var keyGenProgress by remember { mutableFloatStateOf(0f) }
    var keyGenLabel by remember { mutableStateOf("Initializing entropy...") }

    // Permission states
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var isBatteryIgnored by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            } else true
        )
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasMicPermission = it
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasNotifPermission = it
    }

    Scaffold(
        containerColor = AnthropicDarkBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Step Progress Indicator Dots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (i in 0..4) {
                    val isActive = i == currentStep
                    val isPast = i < currentStep
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isActive) 18.dp else 8.dp, 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when {
                                    isActive -> AnthropicTerracotta
                                    isPast -> AnthropicSage
                                    else -> AnthropicDarkNeutral
                                }
                            )
                    )
                }
            }

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "OnboardingSteps"
            ) { step ->
                when (step) {
                    0 -> StepWelcome(
                        onNext = { currentStep = 1 }
                    )
                    1 -> StepPersona(
                        alias = alias,
                        onAliasChange = { alias = it },
                        ephemeralMode = ephemeralMode,
                        onEphemeralChange = { ephemeralMode = it },
                        isGeneratingKeys = isGeneratingKeys,
                        keyGenProgress = keyGenProgress,
                        keyGenLabel = keyGenLabel,
                        onGenerateKeys = {
                            isGeneratingKeys = true
                            scope.launch {
                                keyGenLabel = "Gathering hardware entropy..."
                                keyGenProgress = 0.25f
                                delay(350)
                                keyGenLabel = "Generating OpenPGP Ed25519 Master Key..."
                                keyGenProgress = 0.60f
                                delay(400)
                                keyGenLabel = "Deriving X25519 Encryption Subkey..."
                                keyGenProgress = 0.85f
                                delay(300)
                                keyGenLabel = "Deriving Blind Mailbox Drop ID..."
                                keyGenProgress = 1.0f
                                delay(200)
                                isGeneratingKeys = false
                                currentStep = 2
                            }
                        }
                    )
                    2 -> StepSecurityLock(
                        enablePinLock = enablePinLock,
                        onTogglePinLock = { enablePinLock = it },
                        appPin = appPin,
                        onAppPinChange = { appPin = it; pinError = null },
                        confirmAppPin = confirmAppPin,
                        onConfirmAppPinChange = { confirmAppPin = it; pinError = null },
                        enableDuressPin = enableDuressPin,
                        onToggleDuressPin = { enableDuressPin = it },
                        duressPin = duressPin,
                        onDuressPinChange = { duressPin = it; pinError = null },
                        pinError = pinError,
                        onNext = {
                            if (enablePinLock) {
                                if (appPin.length < 4) {
                                    pinError = "PIN must be at least 4 digits"
                                    return@StepSecurityLock
                                }
                                if (appPin != confirmAppPin) {
                                    pinError = "PINs do not match"
                                    return@StepSecurityLock
                                }
                            }
                            if (enableDuressPin) {
                                if (duressPin.length < 4) {
                                    pinError = "Duress PIN must be at least 4 digits"
                                    return@StepSecurityLock
                                }
                                if (enablePinLock && duressPin == appPin) {
                                    pinError = "Duress PIN cannot be identical to App PIN!"
                                    return@StepSecurityLock
                                }
                            }
                            pinError = null
                            currentStep = 3
                        },
                        onBack = { currentStep = 1 }
                    )
                    3 -> StepPermissions(
                        isBatteryIgnored = isBatteryIgnored,
                        onRequestBattery = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                        },
                        hasCameraPermission = hasCameraPermission,
                        onRequestCamera = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                        hasMicPermission = hasMicPermission,
                        onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        hasNotifPermission = hasNotifPermission,
                        onRequestNotif = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onNext = { currentStep = 4 },
                        onBack = { currentStep = 2 }
                    )
                    4 -> StepReady(
                        alias = alias.ifBlank { "Anonymous" },
                        ephemeralMode = ephemeralMode,
                        hasPinLock = enablePinLock,
                        hasDuressPin = enableDuressPin,
                        onComplete = {
                            onCompleteOnboarding(
                                alias.ifBlank { "GhostKey User" },
                                if (enablePinLock) appPin else null,
                                if (enableDuressPin) duressPin else null,
                                ephemeralMode
                            )
                        },
                        onBack = { currentStep = 3 }
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// STEP 0: Welcome & Philosophy
// -----------------------------------------------------------------------------
@Composable
private fun StepWelcome(onNext: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(AnthropicTerracotta.copy(alpha = 0.15f))
                .border(2.dp, AnthropicTerracotta, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = AnthropicTerracotta,
                modifier = Modifier.size(46.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "GhostKey",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = AnthropicCream,
            letterSpacing = 1.sp
        )

        Text(
            text = "Zero Forensic Residue Messaging",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = AnthropicSage
        )

        Spacer(modifier = Modifier.height(28.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AnthropicSurface, RoundedCornerShape(16.dp))
                .border(1.dp, AnthropicBorderSubtle, RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FeatureRow(
                icon = Icons.Default.Public,
                title = "In-Process Tor Onion Routing",
                desc = "Direct SOCKS5 through embedded Arti Rust Tor engine. No clearnet IP or DNS leaks."
            )
            FeatureRow(
                icon = Icons.Default.Memory,
                title = "RAM-Only In-Memory Architecture",
                desc = "Plaintext messages, audio notes, and temporary state vanish completely when closed. Zero disk footprint."
            )
            FeatureRow(
                icon = Icons.Default.VpnKey,
                title = "Pure End-to-End Cryptography",
                desc = "OpenPGP Ed25519 & X25519 keys generated locally. Relays are blind mailboxes that can never read messages."
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AnthropicTerracotta,
                contentColor = AnthropicDarkBg
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Begin Secure Setup", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

// -----------------------------------------------------------------------------
// STEP 1: Persona & Key Generation
// -----------------------------------------------------------------------------
@Composable
private fun StepPersona(
    alias: String,
    onAliasChange: (String) -> Unit,
    ephemeralMode: Boolean,
    onEphemeralChange: (Boolean) -> Unit,
    isGeneratingKeys: Boolean,
    keyGenProgress: Float,
    keyGenLabel: String,
    onGenerateKeys: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Icon(Icons.Default.Fingerprint, contentDescription = null, tint = AnthropicTerracotta, modifier = Modifier.size(48.dp))

        Text(
            text = "Create Cryptographic Identity",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AnthropicCream,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Your alias is shown only to your peers. No phone numbers, no emails, no central account registry.",
            fontSize = 13.sp,
            color = AnthropicTextMid,
            textAlign = TextAlign.Center
        )

        OutlinedTextField(
            value = alias,
            onValueChange = onAliasChange,
            label = { Text("Display Alias / Nickname", color = AnthropicTextMid) },
            placeholder = { Text("e.g. Shadow, Cipher, Alex", color = AnthropicTextMuted) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isGeneratingKeys,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AnthropicTerracotta,
                unfocusedBorderColor = AnthropicBorder,
                focusedTextColor = AnthropicCream,
                unfocusedTextColor = AnthropicCream,
                focusedContainerColor = AnthropicSurface,
                unfocusedContainerColor = AnthropicSurface
            )
        )

        // Ephemeral Mode Option
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AnthropicSurface, RoundedCornerShape(12.dp))
                .border(1.dp, AnthropicBorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ephemeral Ghost Session", color = AnthropicCream, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Identity is never saved to Android KeyStore; vanishes on exit.", color = AnthropicTextMuted, fontSize = 11.sp)
                }
                Switch(
                    checked = ephemeralMode,
                    onCheckedChange = onEphemeralChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AnthropicTerracotta,
                        checkedTrackColor = AnthropicTerracotta.copy(alpha = 0.4f)
                    ),
                    enabled = !isGeneratingKeys
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (isGeneratingKeys) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(
                    progress = { keyGenProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = AnthropicSage,
                    trackColor = AnthropicDarkNeutral
                )
                Text(keyGenLabel, color = AnthropicSage, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            Button(
                onClick = onGenerateKeys,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AnthropicTerracotta,
                    contentColor = AnthropicDarkBg
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Generate Cryptographic Keys", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// -----------------------------------------------------------------------------
// STEP 2: Screen Lock & Duress PIN
// -----------------------------------------------------------------------------
@Composable
private fun StepSecurityLock(
    enablePinLock: Boolean,
    onTogglePinLock: (Boolean) -> Unit,
    appPin: String,
    onAppPinChange: (String) -> Unit,
    confirmAppPin: String,
    onConfirmAppPinChange: (String) -> Unit,
    enableDuressPin: Boolean,
    onToggleDuressPin: (Boolean) -> Unit,
    duressPin: String,
    onDuressPinChange: (String) -> Unit,
    pinError: String?,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = AnthropicTerracotta, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("App Lock & Duress Protection", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AnthropicCream)
        }

        Text(
            text = "Configure app locking and emergency self-defense mechanisms to protect your identity if your device is inspected.",
            fontSize = 12.5.sp,
            color = AnthropicTextMid
        )

        // 1. App Lock Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AnthropicSurface, RoundedCornerShape(12.dp))
                .border(1.dp, AnthropicBorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("App Lock PIN", color = AnthropicCream, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Requires PIN code whenever opening the app.", color = AnthropicTextMuted, fontSize = 11.sp)
                }
                Switch(
                    checked = enablePinLock,
                    onCheckedChange = onTogglePinLock,
                    colors = SwitchDefaults.colors(checkedThumbColor = AnthropicTerracotta, checkedTrackColor = AnthropicTerracotta.copy(alpha = 0.4f))
                )
            }

            if (enablePinLock) {
                OutlinedTextField(
                    value = appPin,
                    onValueChange = { if (it.length <= 8) onAppPinChange(it) },
                    label = { Text("Choose 4-8 Digit PIN", color = AnthropicTextMid, fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AnthropicTerracotta,
                        unfocusedBorderColor = AnthropicBorder,
                        focusedTextColor = AnthropicCream,
                        unfocusedTextColor = AnthropicCream
                    )
                )

                OutlinedTextField(
                    value = confirmAppPin,
                    onValueChange = { if (it.length <= 8) onConfirmAppPinChange(it) },
                    label = { Text("Confirm PIN", color = AnthropicTextMid, fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AnthropicTerracotta,
                        unfocusedBorderColor = AnthropicBorder,
                        focusedTextColor = AnthropicCream,
                        unfocusedTextColor = AnthropicCream
                    )
                )
            }
        }

        // 2. Duress PIN Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AnthropicSurface, RoundedCornerShape(12.dp))
                .border(1.dp, AnthropicBorderSubtle, RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Duress PIN (Silent Panic Wipe)", color = AnthropicCrimson, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Entering this PIN silently wipes all keys and memory instantly if coerced.", color = AnthropicTextMuted, fontSize = 11.sp)
                }
                Switch(
                    checked = enableDuressPin,
                    onCheckedChange = onToggleDuressPin,
                    colors = SwitchDefaults.colors(checkedThumbColor = AnthropicCrimson, checkedTrackColor = AnthropicCrimson.copy(alpha = 0.4f))
                )
            }

            if (enableDuressPin) {
                OutlinedTextField(
                    value = duressPin,
                    onValueChange = { if (it.length <= 8) onDuressPinChange(it) },
                    label = { Text("Choose Duress PIN (Different from App PIN)", color = AnthropicTextMid, fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AnthropicCrimson,
                        unfocusedBorderColor = AnthropicBorder,
                        focusedTextColor = AnthropicCream,
                        unfocusedTextColor = AnthropicCream
                    )
                )
            }
        }

        if (pinError != null) {
            Text(pinError, color = AnthropicCrimson, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AnthropicCream)
            ) {
                Text("Back")
            }
            Button(
                onClick = onNext,
                modifier = Modifier
                    .weight(2f)
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AnthropicTerracotta, contentColor = AnthropicDarkBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Next: Permissions", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// STEP 3: Transparent Permissions
// -----------------------------------------------------------------------------
@Composable
private fun StepPermissions(
    isBatteryIgnored: Boolean,
    onRequestBattery: () -> Unit,
    hasCameraPermission: Boolean,
    onRequestCamera: () -> Unit,
    hasMicPermission: Boolean,
    onRequestMic: () -> Unit,
    hasNotifPermission: Boolean,
    onRequestNotif: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = AnthropicSage, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("Operational Permissions", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AnthropicCream)
        }

        Text(
            text = "GhostKey requires zero trackers, zero account registrations, and minimal permissions. Every permission serves a strict security purpose:",
            fontSize = 12.5.sp,
            color = AnthropicTextMid
        )

        // 1. Battery Exemption
        PermissionCard(
            icon = Icons.Default.BatteryChargingFull,
            title = "Unrestricted Background Tor",
            desc = "Android kills Tor daemon sockets in the background. Exempting GhostKey guarantees immediate message delivery.",
            isGranted = isBatteryIgnored,
            onAction = onRequestBattery,
            buttonText = "Exempt"
        )

        // 2. Camera Permission
        PermissionCard(
            icon = Icons.Default.CameraAlt,
            title = "Camera for In-Person QR Pairing",
            desc = "Scans friend QR codes in 1 second to verify safety codes and eliminate Man-in-the-Middle attacks.",
            isGranted = hasCameraPermission,
            onAction = onRequestCamera,
            buttonText = "Allow Camera"
        )

        // 3. Mic Permission
        PermissionCard(
            icon = Icons.Default.Mic,
            title = "Encrypted Voice Notes (RAM)",
            desc = "Records Push-to-Talk audio directly in RAM. Zero temporary audio files written to disk storage.",
            isGranted = hasMicPermission,
            onAction = onRequestMic,
            buttonText = "Allow Mic"
        )

        // 4. Notifications
        PermissionCard(
            icon = Icons.Default.Notifications,
            title = "Tor Message Alerts",
            desc = "Notifies you when incoming E2EE messages arrive through your Tor mailbox.",
            isGranted = hasNotifPermission,
            onAction = onRequestNotif,
            buttonText = "Allow Alerts"
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AnthropicCream)
            ) {
                Text("Back")
            }
            Button(
                onClick = onNext,
                modifier = Modifier
                    .weight(2f)
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AnthropicTerracotta, contentColor = AnthropicDarkBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Review & Finish", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// STEP 4: Ready & Enter GhostKey
// -----------------------------------------------------------------------------
@Composable
private fun StepReady(
    alias: String,
    ephemeralMode: Boolean,
    hasPinLock: Boolean,
    hasDuressPin: Boolean,
    onComplete: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(AnthropicSage.copy(alpha = 0.2f))
                .border(2.dp, AnthropicSage, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = AnthropicSage, modifier = Modifier.size(40.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "You Are GhostKey Ready",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = AnthropicCream
        )

        Text(
            text = "Your cryptographic identity is securely prepared.",
            fontSize = 13.sp,
            color = AnthropicTextMid
        )

        Spacer(modifier = Modifier.height(24.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AnthropicSurface, RoundedCornerShape(14.dp))
                .border(1.dp, AnthropicBorderSubtle, RoundedCornerShape(14.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryItem(label = "Persona Alias", value = alias)
            SummaryItem(label = "Storage Mode", value = if (ephemeralMode) "RAM-Only Ghost (Zero persistence)" else "Hardware KeyStore Encrypted")
            SummaryItem(label = "App Lock PIN", value = if (hasPinLock) "Active 🔒" else "Disabled")
            SummaryItem(label = "Duress PIN", value = if (hasDuressPin) "Armed & Ready (Silent Wipe)" else "Not Configured")
            SummaryItem(label = "Network Protocol", value = "Tor Onion Blind Relays + Local Mesh")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AnthropicSage, contentColor = AnthropicDarkBg),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Enter GhostKey", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

// -----------------------------------------------------------------------------
// Helper UI Components
// -----------------------------------------------------------------------------
@Composable
private fun FeatureRow(icon: ImageVector, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(AnthropicDarkNeutral),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = AnthropicTerracotta, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AnthropicCream)
            Text(desc, fontSize = 11.5.sp, color = AnthropicTextMid, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    desc: String,
    isGranted: Boolean,
    onAction: () -> Unit,
    buttonText: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AnthropicSurface, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (isGranted) AnthropicSage.copy(alpha = 0.5f) else AnthropicBorderSubtle,
                RoundedCornerShape(12.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isGranted) AnthropicSage else AnthropicTerracotta,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = AnthropicCream)
            }
            if (isGranted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AnthropicSage, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Granted", color = AnthropicSage, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                OutlinedButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AnthropicTerracotta)
                ) {
                    Text(buttonText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(desc, fontSize = 11.sp, color = AnthropicTextMid, lineHeight = 15.sp)
    }
}

@Composable
private fun SummaryItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AnthropicTextMuted, fontSize = 12.sp)
        Text(value, color = AnthropicCream, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
