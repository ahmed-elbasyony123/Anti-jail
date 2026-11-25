package com.ghostkey.app

import android.Manifest
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.ghostkey.app.audio.AudioPlayerManager
import com.ghostkey.app.audio.AudioRecorderManager
import com.ghostkey.app.audio.AudioRecordResult
import com.ghostkey.app.audio.MemoryAudioDataSource
import com.ghostkey.app.crypto.GroupCrypto
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.crypto.WireFormat
import com.ghostkey.app.crypto.WireMessage
import com.ghostkey.app.data.*
import com.ghostkey.app.net.ArtiTorManager
import com.ghostkey.app.net.ArtiTorState
import com.ghostkey.app.net.EncryptedMessageRecord
import com.ghostkey.app.net.LocalLanMesh
import com.ghostkey.app.net.OrbotPortWatcher
import com.ghostkey.app.net.RelayHttpException
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.net.TorRelayClient
import com.ghostkey.app.notify.PushNotificationManager
import com.ghostkey.app.service.TorForegroundService
import com.ghostkey.app.ui.*
import com.ghostkey.app.ui.dialogs.*
import com.ghostkey.app.ui.screens.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*
import java.util.concurrent.Executors
import kotlin.system.exitProcess

internal const val MAX_BUBBLE_IMAGE_EDGE = 1024

internal fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while ((width / sample) > maxEdge || (height / sample) > maxEdge) sample *= 2
    return sample
}

enum class AppNavScreen { CHATS_LIST, CHAT_VIEW, SETTINGS }

class MainActivity : ComponentActivity() {

    internal val torClient = TorRelayClient()

    // Navigation and Multi-Chat State
    internal var currentNavScreen by mutableStateOf(AppNavScreen.CHATS_LIST)
    internal var activeChatContact by mutableStateOf<Contact?>(null)
    internal var activeChatGroup by mutableStateOf<GroupChat?>(null)
    internal val contacts = mutableStateListOf<Contact>()
    internal val groups = mutableStateListOf<GroupChat>()
    internal var userAlias by mutableStateOf("")

    // Volatile in-memory state - Never saved to SQLite or disk
    internal val messagesState = mutableStateListOf<ChatMessage>()
    internal val relayStatus = mutableStateOf(RelayStatus.DISCONNECTED)

    // Standalone PGP In-Memory State
    internal var myPgpKeys by mutableStateOf<PgpKeyPairArmored?>(null)
    internal var recipientArmoredKey by mutableStateOf("")
    internal var recipientFingerprint by mutableStateOf("")
    internal var recipientDropId by mutableStateOf("")
    internal var isGeneratingKey by mutableStateOf(false)

    // Configuration state
    internal var relayServerUrl by mutableStateOf("")
    internal var useTorSocks by mutableStateOf(false)
    internal var keepAlive by mutableStateOf(false)
    internal var orbotSocksPort = OrbotPortWatcher.DEFAULT_SOCKS_PORT
    internal var orbotWatcher: OrbotPortWatcher? = null

    // Encrypted identity persistence (AndroidKeyStore), ephemeral mode and app lock
    internal lateinit var secureStore: SecureStore
    internal val persistDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    internal var ephemeralMode by mutableStateOf(false)
    internal var lockEnabled by mutableStateOf(false)
    internal var isLocked by mutableStateOf(false)
    internal val maxAttachmentBytes = 15 * 1024 * 1024
    internal val seenNonces = Collections.synchronizedSet(LinkedHashSet<String>())
    internal val sentCiphertexts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Privacy & In-Person Pairing Features: QR Pairing, Disappearing Messages, Duress PIN, Local LAN Mesh, Introductions
    internal var isOnboarding by mutableStateOf(false)
    internal var appPinHash: String? = null
    internal var duressPinHash: String? = null
    internal var showMyQrDialog by mutableStateOf(false)
    internal var showPairContactDialog by mutableStateOf(false)
    internal var showSecurityDiagnostics by mutableStateOf(false)
    internal var localLanMesh: LocalLanMesh? = null
    internal var disappearingReaperJob: kotlinx.coroutines.Job? = null

    // Encrypted PTT Audio Voice Notes (Opus in RAM)
    internal lateinit var audioRecorderManager: AudioRecorderManager
    internal val audioPlayerManager = AudioPlayerManager()
    internal var isRecordingAudio by mutableStateOf(false)
    internal var recordElapsedMs by mutableStateOf(0L)

    // Embedded Arti Tor Engine (Rust) State
    internal var artiState by mutableStateOf<ArtiTorState>(ArtiTorState.Idle)
    internal var artiRetryCount = 0

    companion object {
        var isExternalPickerActive = false
    }

    // Foreground / Background Tracking for Tor Push Notifications
    internal var isForeground = false

    override fun onResume() {
        super.onResume()
        isForeground = true
        isExternalPickerActive = false
        PushNotificationManager.clearMessageNotifications(this)
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        if (isRecordingAudio) {
            audioRecorderManager.cancelRecording()
            isRecordingAudio = false
        }
        if (!ephemeralMode) {
            getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit()
                .putString("relay_url", relayServerUrl)
                .putString("user_alias", userAlias)
                .commit()
            persistIdentitySync()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isExternalPickerActive && lockEnabled && (!appPinHash.isNullOrBlank() || !duressPinHash.isNullOrBlank())) {
            isLocked = true
        }
        if (!ephemeralMode) {
            getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit()
                .putString("relay_url", relayServerUrl)
                .putString("user_alias", userAlias)
                .commit()
            persistIdentitySync()
        }
    }

    private val unlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                isLocked = false
                bootstrapIdentity()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // HARDENING 1: Block screenshots, screen recording, and app thumbnail preview in recents
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Load network config preferences
        val prefs = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE)
        relayServerUrl = prefs.getString("relay_url", "") ?: ""
        useTorSocks = prefs.getBoolean("use_tor", true)
        keepAlive = prefs.getBoolean("keep_alive", true)
        orbotSocksPort = prefs.getInt("orbot_socks_port", OrbotPortWatcher.DEFAULT_SOCKS_PORT)
        ephemeralMode = prefs.getBoolean("ephemeral_mode", false)
        lockEnabled = prefs.getBoolean("lock_enabled", false)
        userAlias = prefs.getString("user_alias", "") ?: ""
        appPinHash = prefs.getString("app_pin_hash", null)
        duressPinHash = prefs.getString("duress_pin_hash", null)

        secureStore = SecureStore(applicationContext)
        audioRecorderManager = AudioRecorderManager(applicationContext)
        localLanMesh = LocalLanMesh(applicationContext) { senderDropId, ciphertext ->
            val msgId = UUID.randomUUID().toString()
            val incoming = ChatMessage(
                id = msgId,
                isOutgoing = false,
                ciphertext = ciphertext,
                timestamp = System.currentTimeMillis(),
                isDecrypted = false,
                chatId = senderDropId
            )
            messagesState.add(incoming)
            decryptMessage(incoming, senderDropId)
        }

        // 1. Initialize Embedded Arti Tor (Rust engine) or fallback to external Orbot FIRST
        initTorNetworkEngine()
        applyNetworkConfig()

        val hasCompletedOnboarding = prefs.getBoolean("has_completed_onboarding", false)

        // 2. Bring up the identity or start onboarding
        if (!hasCompletedOnboarding && !secureStore.hasIdentity()) {
            isOnboarding = true
        } else if (lockEnabled && !ephemeralMode && (!appPinHash.isNullOrBlank() || !duressPinHash.isNullOrBlank())) {
            isLocked = true
        } else {
            bootstrapIdentity()
        }
        PushNotificationManager.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }
        if (keepAlive) TorForegroundService.start(this)
        startDisappearingMessagesReaper()

        setContent {
            GhostKeyTheme {
                if (isOnboarding) {
                    WelcomeOnboardingScreen(
                        onCompleteOnboarding = { alias, appPin, duressPin, isEphemeral ->
                            userAlias = alias
                            ephemeralMode = isEphemeral
                            val editor = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit()
                            editor.putString("user_alias", alias)
                            editor.putBoolean("ephemeral_mode", isEphemeral)
                            editor.putBoolean("has_completed_onboarding", true)
                            if (!appPin.isNullOrBlank()) {
                                val hash = hashPin(appPin)
                                appPinHash = hash
                                lockEnabled = true
                                editor.putBoolean("lock_enabled", true)
                                editor.putString("app_pin_hash", hash)
                            }
                            if (!duressPin.isNullOrBlank()) {
                                val hash = hashPin(duressPin)
                                duressPinHash = hash
                                editor.putString("duress_pin_hash", hash)
                            }
                            editor.commit()

                            generateFreshKeys(alias)
                            isOnboarding = false
                        }
                    )
                } else if (isLocked) {
                    LockedScreen(
                        hasDuressPin = !duressPinHash.isNullOrBlank(),
                        onUnlock = {
                            isLocked = false
                            if (myPgpKeys == null) {
                                bootstrapIdentity()
                            }
                        },
                        onDuressEntered = { triggerEmergencyPanicWipe() },
                        onDeviceUnlock = { requestUnlock() },
                        onVerifyPin = { pin ->
                            val hash = hashPin(pin)
                            if (!duressPinHash.isNullOrBlank() && hash == duressPinHash) {
                                // Duress PIN entered — silent panic wipe
                                triggerEmergencyPanicWipe()
                                false
                            } else if (!appPinHash.isNullOrBlank() && hash == appPinHash) {
                                true
                            } else if (appPinHash.isNullOrBlank() && duressPinHash.isNullOrBlank()) {
                                true
                            } else {
                                false
                            }
                        }
                    )
                } else {
                    MainModernApp()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        localLanMesh?.stop()
        ArtiTorManager.stop()
        audioRecorderManager.cancelRecording()
        audioPlayerManager.stop()
        orbotWatcher?.stop()
        torClient.disconnectWebSocket()
        if (isFinishing) TorForegroundService.stop(this)
    }

    private fun initTorNetworkEngine() {
        if (ArtiTorManager.isAvailable) {
            CoroutineScope(Dispatchers.Main).launch {
                ArtiTorManager.state.collect { state ->
                    artiState = state
                    when (state) {
                        is ArtiTorState.Starting -> {
                            relayStatus.value = RelayStatus.BUILDING_CIRCUIT
                            if (state.socksPort > 0 && orbotSocksPort != state.socksPort) {
                                orbotSocksPort = state.socksPort
                                applyNetworkConfig()
                            }
                        }
                        is ArtiTorState.Connected -> {
                            orbotSocksPort = state.socksPort
                            applyNetworkConfig()
                            myPgpKeys?.let { keys ->
                                connectToRelayMailbox(keys)
                                for (g in groups) {
                                    torClient.addSubscription(g.groupDropId, g.groupReadToken)
                                }
                            }
                        }
                        is ArtiTorState.Error -> {
                            android.util.Log.w("GhostKey", "Arti Tor error: ${state.message}, retrying or falling back to Orbot")
                            relayStatus.value = RelayStatus.BUILDING_CIRCUIT
                            CoroutineScope(Dispatchers.IO).launch {
                                delay(3000)
                                if (artiRetryCount < 2 && (useTorSocks || relayServerUrl.contains(".onion"))) {
                                    artiRetryCount++
                                    ArtiTorManager.start(applicationContext)
                                } else {
                                    runOnUiThread { startOrbotWatcher() }
                                }
                            }
                        }
                        is ArtiTorState.Idle -> {}
                    }
                }
            }
            if (useTorSocks || relayServerUrl.contains(".onion")) {
                CoroutineScope(Dispatchers.IO).launch {
                    ArtiTorManager.start(applicationContext)
                }
            }
        } else {
            startOrbotWatcher()
        }
    }

    private fun startOrbotWatcher() {
        if (orbotWatcher == null) {
            orbotWatcher = OrbotPortWatcher(this) { port -> runOnUiThread { onOrbotPort(port) } }.also { it.start() }
        }
    }

    private fun applyNetworkConfig() {
        torClient.updateConfig(relayServerUrl, useTorSocks, socksPort = orbotSocksPort)
    }

    private fun onOrbotPort(port: Int) {
        val changed = orbotSocksPort != port
        orbotSocksPort = port
        getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit()
            .putInt("orbot_socks_port", port).apply()
        applyNetworkConfig()
        if (changed) {
            myPgpKeys?.let { connectToRelayMailbox(it) }
        }
    }

    private fun updateKeepAlive(enabled: Boolean) {
        keepAlive = enabled
        getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("keep_alive", enabled).apply()
        if (enabled) {
            TorForegroundService.start(this)
        } else {
            TorForegroundService.stop(this)
        }
    }

    private fun requestUnlock() {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        val intent = keyguard.createConfirmDeviceCredentialIntent("GhostKey", "Unlock to open your saved identity")
        if (intent == null) {
            if (appPinHash.isNullOrBlank()) {
                lockEnabled = false
                getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit().putBoolean("lock_enabled", false).apply()
                isLocked = false
                Toast.makeText(this, "No device screen lock is set, so the app lock was turned off", Toast.LENGTH_LONG).show()
                bootstrapIdentity()
            } else {
                Toast.makeText(this, "Device lock screen not configured. Please use your App PIN.", Toast.LENGTH_SHORT).show()
            }
        } else {
            unlockLauncher.launch(intent)
        }
    }

    private fun bootstrapIdentity() {
        if (ephemeralMode || !secureStore.hasIdentity()) {
            generateFreshKeys()
            return
        }
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val stored = secureStore.load()
                runOnUiThread { applyIdentity(stored) }
            } catch (e: Exception) {
                secureStore.wipe()
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "The saved identity could not be read, so a new one was created",
                        Toast.LENGTH_LONG
                    ).show()
                    generateFreshKeys()
                }
            }
        }
    }

    private fun applyIdentity(stored: StoredIdentity) {
        myPgpKeys = stored.mine
        if (stored.userAlias.isNotBlank()) {
            userAlias = stored.userAlias
        } else {
            val prefAlias = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).getString("user_alias", "") ?: ""
            if (prefAlias.isNotBlank()) {
                userAlias = prefAlias
            }
        }
        if (stored.relayUrl.isNotBlank()) {
            relayServerUrl = stored.relayUrl
        } else {
            val prefRelay = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).getString("relay_url", "") ?: ""
            if (prefRelay.isNotBlank()) {
                relayServerUrl = prefRelay
            }
        }
        duressPinHash = stored.duressPinHash
        contacts.clear()
        contacts.addAll(stored.contacts)
        groups.clear()
        groups.addAll(stored.groups)

        stored.peer?.let {
            recipientArmoredKey = it.publicKeyArmored
            recipientFingerprint = it.fingerprint
            recipientDropId = it.dropId
            if (contacts.none { c -> c.dropId == it.dropId }) {
                contacts.add(
                    Contact(
                        id = it.dropId,
                        alias = "Peer ${it.fingerprint.take(6)}",
                        dropId = it.dropId,
                        publicKeyArmored = it.publicKeyArmored,
                        fingerprint = it.fingerprint
                    )
                )
            }
        }

        val requiresTor = useTorSocks || relayServerUrl.contains(".onion")
        if (requiresTor && ArtiTorManager.isAvailable && artiState !is ArtiTorState.Connected) {
            relayStatus.value = RelayStatus.BUILDING_CIRCUIT
        } else {
            connectToRelayMailbox(stored.mine)
            for (g in groups) {
                torClient.addSubscription(g.groupDropId, g.groupReadToken)
            }
        }
    }

    private fun persistIdentitySync() {
        if (ephemeralMode) return
        val mine = myPgpKeys ?: return
        val peer = if (recipientArmoredKey.isNotBlank()) {
            PeerIdentity(recipientArmoredKey, recipientFingerprint, recipientDropId)
        } else null
        val snapshot = StoredIdentity(
            mine = mine,
            peer = peer,
            userAlias = userAlias,
            relayUrl = relayServerUrl,
            contacts = contacts.toList(),
            groups = groups.toList(),
            duressPinHash = duressPinHash
        )
        try {
            secureStore.save(snapshot)
        } catch (e: Exception) {
            android.util.Log.e("GhostKey", "Sync identity save failed", e)
        }
    }

    internal fun persistIdentity() {
        persistIdentitySync()
    }

    private fun updateEphemeralMode(enabled: Boolean) {
        ephemeralMode = enabled
        val editor = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit()
        editor.putBoolean("ephemeral_mode", enabled)
        if (enabled) {
            editor.remove("relay_url")
            editor.remove("user_alias")
            editor.commit()
            CoroutineScope(persistDispatcher).launch { secureStore.wipe() }
        } else {
            editor.putString("relay_url", relayServerUrl)
            editor.putString("user_alias", userAlias)
            editor.commit()
            persistIdentitySync()
        }
    }

    private fun updateLockEnabled(enabled: Boolean) {
        if (enabled && appPinHash.isNullOrBlank()) {
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguard.isDeviceSecure) {
                Toast.makeText(
                    this,
                    "Set an App PIN below or configure a screen lock in Android settings",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        lockEnabled = enabled
        getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit().putBoolean("lock_enabled", enabled).apply()
    }

    internal fun generateFreshKeys(alias: String? = null) {
        if (!alias.isNullOrBlank()) {
            userAlias = alias
        }
        isGeneratingKey = true
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val pair = StandalonePgpEngine.generateKeyPair(userAlias.ifBlank { null })
                runOnUiThread {
                    myPgpKeys = pair
                    isGeneratingKey = false
                    for (m in messagesState) {
                        m.zeroOut()
                    }
                    messagesState.clear()
                    synchronized(seenNonces) { seenNonces.clear() }
                    synchronized(sentCiphertexts) { sentCiphertexts.clear() }
                    StandalonePgpEngine.clearCache()
                    persistIdentity()
                    val requiresTor = useTorSocks || relayServerUrl.contains(".onion")
                    if (requiresTor && ArtiTorManager.isAvailable && artiState !is ArtiTorState.Connected) {
                        relayStatus.value = RelayStatus.BUILDING_CIRCUIT
                    } else {
                        connectToRelayMailbox(pair)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isGeneratingKey = false
                    Toast.makeText(this@MainActivity, "Key generation failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun connectToRelayMailbox(keys: PgpKeyPairArmored) {
        localLanMesh?.start(keys.dropId)
        torClient.subscribeLive(
            dropId = keys.dropId,
            readToken = keys.readToken,
            onMessageReceived = { record -> runOnUiThread { handleIncoming(record) } },
            onStatusChanged = { status -> runOnUiThread { relayStatus.value = status } }
        )
        for (g in groups) {
            torClient.addSubscription(g.groupDropId, g.groupReadToken)
        }
    }


    internal fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied! (auto-clears in 45s) 📋", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            delay(45_000L)
            try {
                if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == value) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) {}
        }
    }

    internal fun copyKeyToClipboard(keyText: String) {
        copyToClipboard("Public Key", keyText)
    }

    private fun hashPin(pin: String): String {
        val normalized = pin.trim()
        val salt = "ghostkey-pin-salt-v2".toByteArray(Charsets.UTF_8)
        val spec = javax.crypto.spec.PBEKeySpec(normalized.toCharArray(), salt, 65536, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return hash.joinToString("") { "%02x".format(it) }
    }

    internal fun setAppPin(pin: String?) {
        val hash = if (pin != null) hashPin(pin) else null
        appPinHash = hash
        val prefs = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE)
        if (hash != null) {
            lockEnabled = true
            prefs.edit()
                .putString("app_pin_hash", hash)
                .putBoolean("lock_enabled", true)
                .apply()
        } else {
            lockEnabled = false
            prefs.edit()
                .remove("app_pin_hash")
                .putBoolean("lock_enabled", false)
                .apply()
        }
        persistIdentity()
    }

    internal fun setDuressPin(pin: String?) {
        val hash = if (pin != null) hashPin(pin) else null
        duressPinHash = hash
        val prefs = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE)
        if (hash != null) {
            prefs.edit().putString("duress_pin_hash", hash).apply()
        } else {
            prefs.edit().remove("duress_pin_hash").apply()
        }
        persistIdentity()
    }

    private fun startDisappearingMessagesReaper() {
        disappearingReaperJob?.cancel()
        disappearingReaperJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                delay(1_000L) // Check every 1 second for exact countdown expiration
                val now = System.currentTimeMillis()
                val expired = messagesState.filter { it.isExpired(now) }
                if (expired.isNotEmpty()) {
                    for (msg in expired) {
                        msg.zeroOut() // Zero audio bytes in RAM
                    }
                    messagesState.removeAll(expired.toSet())
                }
            }
        }
    }

    internal fun getDisappearingTtlForChat(chatId: String): Long? {
        return contacts.find { it.dropId == chatId }?.disappearingTtlSeconds
            ?: groups.find { it.id == chatId }?.let { null } // Groups don't have per-contact TTL yet
    }

    internal fun setDisappearingTimerForContact(contactDropId: String, ttlSeconds: Long?) {
        val idx = contacts.indexOfFirst { it.dropId == contactDropId }
        if (idx >= 0) {
            contacts[idx] = contacts[idx].copy(disappearingTtlSeconds = ttlSeconds)
            persistIdentity()
        }
    }

    internal fun triggerEmergencyPanicWipe() {
        try { persistDispatcher.close() } catch (_: Exception) {}
        for (m in messagesState) {
            m.zeroOut()
        }
        messagesState.clear()
        ArtiTorManager.wipe(applicationContext)
        ArtiTorManager.stop()
        audioPlayerManager.wipe()
        audioRecorderManager.cancelRecording()
        StandalonePgpEngine.clearCache()
        secureStore.wipe()
        getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {}
        seenNonces.clear()
        sentCiphertexts.clear()
        myPgpKeys = null
        duressPinHash = null
        appPinHash = null
        contacts.clear()
        groups.clear()
        userAlias = ""
        recipientArmoredKey = ""
        recipientFingerprint = ""
        recipientDropId = ""
        torClient.wipe()
        TorForegroundService.stop(this)
        finishAffinity()
        exitProcess(0)
    }

    internal fun saveAllSettings(
        newUrl: String,
        newTor: Boolean,
        newKeepAlive: Boolean,
        newEphemeral: Boolean,
        newLock: Boolean,
        newAlias: String
    ) {
        var cleanUrl = newUrl.trim().trimEnd('/')
        if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = if (cleanUrl.contains(".onion", ignoreCase = true) || cleanUrl.startsWith("127.0.0.1") || cleanUrl.startsWith("localhost") || cleanUrl.startsWith("10.0.2.2")) {
                "http://$cleanUrl"
            } else {
                "https://$cleanUrl"
            }
        }
        relayServerUrl = cleanUrl
        useTorSocks = newTor
        userAlias = newAlias.trim()
        updateKeepAlive(newKeepAlive)
        updateEphemeralMode(newEphemeral)
        updateLockEnabled(newLock)

        val p = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE)
        val editor = p.edit()
        if (!newEphemeral) {
            editor.putString("relay_url", relayServerUrl)
            editor.putString("user_alias", userAlias)
        } else {
            editor.remove("relay_url")
            editor.remove("user_alias")
        }
        editor.putBoolean("use_tor", useTorSocks)
        editor.commit()
        applyNetworkConfig()
        persistIdentitySync()
        myPgpKeys?.let { connectToRelayMailbox(it) }
    }

    internal fun onAutoSaveSettings(newUrl: String, newAlias: String) {
        var cleanUrl = newUrl.trim().trimEnd('/')
        if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = if (cleanUrl.contains(".onion", ignoreCase = true) || cleanUrl.startsWith("127.0.0.1") || cleanUrl.startsWith("localhost") || cleanUrl.startsWith("10.0.2.2")) {
                "http://$cleanUrl"
            } else {
                "https://$cleanUrl"
            }
        }
        relayServerUrl = cleanUrl
        userAlias = newAlias.trim()
        if (!ephemeralMode) {
            getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit()
                .putString("relay_url", relayServerUrl)
                .putString("user_alias", userAlias)
                .putBoolean("has_completed_onboarding", true)
                .commit()
            persistIdentitySync()
        }
    }

    internal fun computeChatSummaries(): List<ChatSummary> {
        val list = mutableListOf<ChatSummary>()
        val knownChatIds = mutableSetOf<String>()
        for (g in groups) {
            knownChatIds.add(g.id)
            knownChatIds.add(g.groupDropId)
            val lastMsg = messagesState.filter { it.chatId == g.id }.lastOrNull()
            list.add(
                ChatSummary(
                    chatId = g.id,
                    title = g.name,
                    subtitle = "${g.members.size} members",
                    isGroup = true,
                    lastMessageText = lastMsg?.plaintext ?: if (lastMsg?.isAudio == true) "🎙️ Voice note" else if (lastMsg?.isMedia == true) "📎 Attachment" else "Group session ready",
                    lastMessageTime = lastMsg?.timestamp ?: g.createdAt,
                    avatarLetter = g.name.take(1)
                )
            )
        }
        for (c in contacts) {
            knownChatIds.add(c.dropId)
            val lastMsg = messagesState.filter { it.chatId == c.dropId }.lastOrNull()
            list.add(
                ChatSummary(
                    chatId = c.dropId,
                    title = c.alias,
                    subtitle = "Peer ${c.fingerprint.take(8)}",
                    isGroup = false,
                    lastMessageText = lastMsg?.plaintext ?: if (lastMsg?.isAudio == true) "🎙️ Voice note" else if (lastMsg?.isMedia == true) "📎 Attachment" else "Direct E2EE session",
                    lastMessageTime = lastMsg?.timestamp ?: c.addedAt,
                    avatarLetter = c.alias.take(1),
                    isVerified = c.isVerified
                )
            )
        }
        // Include unknown / mailbox incoming messages so they are never silently hidden
        val unknownMessages = messagesState.filter { it.chatId !in knownChatIds && !it.isOutgoing }
        val unknownByChat = unknownMessages.groupBy { it.chatId }
        for ((cid, msgs) in unknownByChat) {
            val lastMsg = msgs.lastOrNull() ?: continue
            val sender = lastMsg.senderAlias ?: lastMsg.signerFingerprint?.take(8) ?: "Unknown"
            list.add(
                ChatSummary(
                    chatId = cid,
                    title = "Unknown ($sender)",
                    subtitle = "Unsaved Contact",
                    isGroup = false,
                    lastMessageText = lastMsg.plaintext ?: if (lastMsg.isAudio) "🎙️ Voice note" else if (lastMsg.isMedia) "📎 Attachment" else "Incoming message",
                    lastMessageTime = lastMsg.timestamp,
                    avatarLetter = sender.take(1).uppercase()
                )
            )
        }
        return list.sortedByDescending { it.lastMessageTime }
    }


    internal fun onMicClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val ok = audioRecorderManager.startRecording()
            if (ok) {
                isRecordingAudio = true
                recordElapsedMs = 0L
            } else {
                Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            }
        } else {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val ok = audioRecorderManager.startRecording()
            if (ok) {
                isRecordingAudio = true
                recordElapsedMs = 0L
            }
        } else {
            Toast.makeText(this, "Microphone permission required for voice notes", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun onStopAudioRecording() {
        isRecordingAudio = false
        val result = audioRecorderManager.stopRecording() ?: return
        val group = activeChatGroup
        val contact = activeChatContact
        if (group != null) {
            val payload = WireFormat.buildMedia(result.mimeType, result.filename, result.bytes, senderAlias = userAlias, groupId = group.id)
            val ciphertext = GroupCrypto.encrypt(payload, group.sharedKeyBase64)
            sentCiphertexts.add(ciphertext)
            CoroutineScope(Dispatchers.Default).launch {
                torClient.sendMessage(dropId = group.groupDropId, ciphertext = ciphertext)
                runOnUiThread {
                    val msg = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        isOutgoing = true,
                        ciphertext = ciphertext,
                        timestamp = System.currentTimeMillis(),
                        isDecrypted = true,
                        isMedia = true,
                        isAudio = true,
                        mimeType = result.mimeType,
                        filename = result.filename,
                        audioBytes = result.bytes,
                        audioDurationMs = result.durationMs,
                        chatId = group.id,
                        groupId = group.id,
                        isGroup = true,
                        senderAlias = userAlias
                    )
                    messagesState.add(msg)
                }
            }
        } else if (contact != null) {
            recipientArmoredKey = contact.publicKeyArmored
            recipientFingerprint = contact.fingerprint
            recipientDropId = contact.dropId
            sendAudioRecordResult(result, targetChatId = contact.dropId)
        } else {
            sendAudioRecordResult(result)
        }
    }

    internal fun onCancelAudioRecording() {
        isRecordingAudio = false
        audioRecorderManager.cancelRecording()
    }

}
