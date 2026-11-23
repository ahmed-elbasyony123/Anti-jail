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

private const val MAX_BUBBLE_IMAGE_EDGE = 1024

internal fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while ((width / sample) > maxEdge || (height / sample) > maxEdge) sample *= 2
    return sample
}

enum class AppNavScreen { CHATS_LIST, CHAT_VIEW, SETTINGS }

class MainActivity : ComponentActivity() {

    private val torClient = TorRelayClient()

    // Navigation and Multi-Chat State
    private var currentNavScreen by mutableStateOf(AppNavScreen.CHATS_LIST)
    private var activeChatContact by mutableStateOf<Contact?>(null)
    private var activeChatGroup by mutableStateOf<GroupChat?>(null)
    private val contacts = mutableStateListOf<Contact>()
    private val groups = mutableStateListOf<GroupChat>()
    private var userAlias by mutableStateOf("")

    // Volatile in-memory state - Never saved to SQLite or disk
    private val messagesState = mutableStateListOf<ChatMessage>()
    private val relayStatus = mutableStateOf(RelayStatus.DISCONNECTED)

    // Standalone PGP In-Memory State
    private var myPgpKeys by mutableStateOf<PgpKeyPairArmored?>(null)
    private var recipientArmoredKey by mutableStateOf("")
    private var recipientFingerprint by mutableStateOf("")
    private var recipientDropId by mutableStateOf("")
    private var isGeneratingKey by mutableStateOf(false)

    // Configuration state
    private var relayServerUrl by mutableStateOf("")
    private var useTorSocks by mutableStateOf(false)
    private var keepAlive by mutableStateOf(false)
    private var orbotSocksPort = OrbotPortWatcher.DEFAULT_SOCKS_PORT
    private var orbotWatcher: OrbotPortWatcher? = null

    // Encrypted identity persistence (AndroidKeyStore), ephemeral mode and app lock
    private lateinit var secureStore: SecureStore
    private val persistDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var ephemeralMode by mutableStateOf(false)
    private var lockEnabled by mutableStateOf(false)
    private var isLocked by mutableStateOf(false)
    private val maxAttachmentBytes = 15 * 1024 * 1024
    private val seenNonces = Collections.synchronizedSet(LinkedHashSet<String>())
    private val sentCiphertexts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Privacy & In-Person Pairing Features: QR Pairing, Disappearing Messages, Duress PIN, Local LAN Mesh, Introductions
    private var isOnboarding by mutableStateOf(false)
    private var appPinHash: String? = null
    private var duressPinHash: String? = null
    private var showMyQrDialog by mutableStateOf(false)
    private var showPairContactDialog by mutableStateOf(false)
    private var showSecurityDiagnostics by mutableStateOf(false)
    private var localLanMesh: LocalLanMesh? = null
    private var disappearingReaperJob: kotlinx.coroutines.Job? = null

    // Encrypted PTT Audio Voice Notes (Opus in RAM)
    private lateinit var audioRecorderManager: AudioRecorderManager
    private val audioPlayerManager = AudioPlayerManager()
    private var isRecordingAudio by mutableStateOf(false)
    private var recordElapsedMs by mutableStateOf(0L)

    // Embedded Arti Tor Engine (Rust) State
    private var artiState by mutableStateOf<ArtiTorState>(ArtiTorState.Idle)
    private var artiRetryCount = 0

    companion object {
        var isExternalPickerActive = false
    }

    // Foreground / Background Tracking for Tor Push Notifications
    private var isForeground = false

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

    private fun persistIdentity() {
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

    private fun generateFreshKeys(alias: String? = null) {
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

    private fun handleIncoming(record: EncryptedMessageRecord) {
        if (sentCiphertexts.contains(record.ciphertext)) {
            // Echo of our own outgoing message on shared groupDropId
            torClient.ack(listOf(record.id), dropId = record.dropId)
            return
        }
        val existing = messagesState.firstOrNull { it.id == record.id }
        if (existing != null) {
            if (existing.isDecrypted) torClient.ack(listOf(existing.id), dropId = record.dropId)
            return
        }
        val msg = ChatMessage(
            id = record.id,
            isOutgoing = false,
            plaintext = null,
            ciphertext = record.ciphertext,
            timestamp = record.createdAt,
            isDecrypted = false,
            chatId = record.dropId
        )
        messagesState.add(msg)
        decryptMessage(msg, record.dropId)
    }

    private fun sendMedia(uri: Uri, targetChatId: String = "") {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it, maxAttachmentBytes) }
                if (bytes == null) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "File exceeds 15 MB limit", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                val name = queryFilename(uri) ?: "file"
                var preview: ImageBitmap? = null
                if (mime.startsWith("image/")) {
                    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                    val opt = BitmapFactory.Options().apply {
                        inSampleSize = calculateInSampleSize(b.outWidth, b.outHeight, MAX_BUBBLE_IMAGE_EDGE)
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)?.let {
                        preview = it.asImageBitmap()
                    }
                }
                val payload = WireFormat.buildMedia(mime, name, bytes, senderAlias = userAlias)
                runOnUiThread {
                    sendPayload(
                        payload = payload,
                        isMedia = true,
                        mimeType = mime,
                        filename = name,
                        mediaBitmap = preview,
                        targetChatId = targetChatId
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Could not read file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun queryFilename(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return null
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun sendMessage(text: String, targetChatId: String = "") {
        if (text.isBlank()) return
        val finalChatId = targetChatId.ifBlank { recipientDropId }
        val ttl = getDisappearingTtlForChat(finalChatId)
        sendPayload(
            payload = WireFormat.buildText(text, senderAlias = userAlias, ttlSeconds = ttl),
            isMedia = false,
            displayText = text,
            targetChatId = targetChatId
        )
    }

    private fun sendAudioRecordResult(result: AudioRecordResult, targetChatId: String = "") {
        val finalChatId = targetChatId.ifBlank { recipientDropId }
        val ttl = getDisappearingTtlForChat(finalChatId)
        val payload = WireFormat.buildMedia(result.mimeType, result.filename, result.bytes, senderAlias = userAlias, ttlSeconds = ttl)
        sendPayload(
            payload = payload,
            isMedia = true,
            isAudio = true,
            mimeType = result.mimeType,
            filename = result.filename,
            audioBytes = result.bytes,
            audioDurationMs = result.durationMs,
            targetChatId = targetChatId
        )
    }

    private fun sendPayload(
        payload: ByteArray,
        isMedia: Boolean = false,
        isAudio: Boolean = false,
        displayText: String? = null,
        mimeType: String = "",
        filename: String = "",
        mediaBitmap: ImageBitmap? = null,
        audioBytes: ByteArray? = null,
        audioDurationMs: Long = 0L,
        targetChatId: String = ""
    ) {
        val keys = myPgpKeys
        if (keys == null) {
            Toast.makeText(this, "No active PGP key in RAM. Please generate keys first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (recipientArmoredKey.isBlank()) {
            Toast.makeText(this, "Please select or paste your peer's public key first!", Toast.LENGTH_LONG).show()
            return
        }

        if (recipientDropId.isBlank()) {
            Toast.makeText(
                this,
                "Peer key has no Drop-ID.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val peerKey = recipientArmoredKey
        val peerDrop = recipientDropId
        val finalChatId = targetChatId.ifBlank { peerDrop }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val ciphertext = StandalonePgpEngine.encrypt(payload, peerKey, keys.privateKeyArmored)
                // Attempt Local LAN direct mesh delivery first (offline capability)
                val sentViaLan = localLanMesh?.sendDirect(peerDrop, ciphertext) == true
                val result = if (sentViaLan) {
                    Result.success(Unit)
                } else {
                    torClient.sendMessage(dropId = peerDrop, ciphertext = ciphertext)
                }
                result.onSuccess {
                    runOnUiThread {
                        val cleanText = if (isMedia) null else (displayText ?: WireFormat.parse(payload)?.text ?: String(payload, Charsets.UTF_8))
                        val wire = WireFormat.parse(payload)
                        val ttl = wire?.ttlSeconds
                        val expires = if (ttl != null && ttl > 0) System.currentTimeMillis() + (ttl * 1000L) else null
                        val msg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            isOutgoing = true,
                            plaintext = cleanText,
                            ciphertext = ciphertext,
                            timestamp = System.currentTimeMillis(),
                            isDecrypted = true,
                            isMedia = isMedia,
                            isAudio = isAudio,
                            mimeType = mimeType,
                            filename = filename,
                            mediaBitmap = mediaBitmap,
                            audioBytes = audioBytes,
                            audioDurationMs = audioDurationMs,
                            chatId = finalChatId,
                            senderAlias = userAlias,
                            expiresAt = expires,
                            ttlSeconds = ttl
                        )
                        messagesState.add(msg)
                    }
                }.onFailure { err ->
                    runOnUiThread {
                        val reason = when ((err as? RelayHttpException)?.code) {
                            429 -> "rate limit reached, try again shortly"
                            503 -> "the relay is at capacity"
                            413 -> "message is too large for the relay"
                            else -> err.message
                        }
                        Toast.makeText(this@MainActivity, "Send failed: $reason", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Encryption error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun decryptMessage(msg: ChatMessage, dropId: String) {
        val keys = myPgpKeys ?: return

        // Check if message belongs to a known group
        val group = groups.find { it.groupDropId == dropId || it.id == dropId }
        if (group != null) {
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val plainBytes = GroupCrypto.decrypt(msg.ciphertext, group.sharedKeyBase64)
                    val wire = WireFormat.parse(plainBytes)

                    // Anti-replay check for group messages
                    if (wire != null && wire.nonce != null) {
                        synchronized(seenNonces) {
                            if (seenNonces.contains(wire.nonce)) {
                                torClient.ack(listOf(msg.id), dropId = group.groupDropId)
                                return@launch
                            }
                            seenNonces.add(wire.nonce)
                            if (seenNonces.size > 2000) {
                                val first = seenNonces.iterator().next()
                                seenNonces.remove(first)
                            }
                        }
                    }

                    var isMedia = false
                    var isAudio = false
                    var mimeType = ""
                    var filename = ""
                    var previewBitmap: ImageBitmap? = null
                    var audioBytes: ByteArray? = null
                    var audioDurationMs = 0L
                    var displayText: String? = null

                    var isIntro = false
                var introAlias: String? = null
                var introDropId: String? = null
                var introArmoredKey: String? = null
                var introSafetyCode: String? = null

                if (wire != null && wire.isIntroduction) {
                    isIntro = true
                    introAlias = wire.introAlias
                    introDropId = wire.introDropId
                    introArmoredKey = wire.introArmoredKey
                    introSafetyCode = wire.introSafetyCode
                    displayText = "🤝 Introduced contact: ${wire.introAlias}"
                } else if (wire != null && wire.isMedia) {
                        isMedia = true
                        mimeType = wire.mimeType ?: ""
                        filename = wire.filename ?: ""
                        if (mimeType.startsWith("image/")) {
                            try {
                                val mediaBytes = WireFormat.decodeMediaData(wire)
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(mediaBytes, 0, mediaBytes.size, bounds)
                                val options = BitmapFactory.Options().apply {
                                    inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_BUBBLE_IMAGE_EDGE)
                                }
                                BitmapFactory.decodeByteArray(mediaBytes, 0, mediaBytes.size, options)?.let {
                                    previewBitmap = it.asImageBitmap()
                                }
                            } catch (_: Throwable) {}
                        } else if (mimeType.startsWith("audio/")) {
                            isAudio = true
                            try {
                                val rawBytes = WireFormat.decodeMediaData(wire)
                                audioBytes = rawBytes
                                val retriever = MediaMetadataRetriever()
                                val ds = MemoryAudioDataSource(rawBytes)
                                retriever.setDataSource(ds)
                                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                audioDurationMs = durStr?.toLongOrNull() ?: 0L
                                retriever.release()
                            } catch (_: Throwable) {}
                        }
                    } else {
                        displayText = wire?.text ?: String(plainBytes, Charsets.UTF_8)
                    }

                    val wireTtl = wire?.ttlSeconds
                    val expires = if (wireTtl != null && wireTtl > 0) System.currentTimeMillis() + (wireTtl * 1000L) else null

                    runOnUiThread {
                        val index = messagesState.indexOfFirst { it.id == msg.id }
                        if (index != -1) {
                            messagesState[index] = messagesState[index].copy(
                                plaintext = displayText,
                                isDecrypted = true,
                                isMedia = isMedia,
                                isAudio = isAudio,
                                mimeType = mimeType,
                                filename = filename,
                                mediaBitmap = previewBitmap,
                                audioBytes = audioBytes,
                                audioDurationMs = audioDurationMs,
                                chatId = group.id,
                                groupId = group.id,
                                isGroup = true,
                                senderAlias = wire?.senderAlias,
                                expiresAt = expires,
                                ttlSeconds = wireTtl,
                                isIntroduction = isIntro,
                                introAlias = introAlias,
                                introDropId = introDropId,
                                introArmoredKey = introArmoredKey,
                                introSafetyCode = introSafetyCode
                            )
                            torClient.ack(listOf(msg.id), dropId = group.groupDropId)
                        }
                    }

                    if (!isForeground) {
                        PushNotificationManager.postMessageNotification(
                            context = this@MainActivity,
                            isAudio = isAudio,
                            hasMedia = isMedia,
                            previewText = if (!isAudio && !isMedia) displayText else null
                        )
                    }
                } catch (_: Exception) {}
            }
            return
        }

        // Direct 1-to-1 PGP message
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val candidateKeys = (contacts.mapNotNull { it.publicKeyArmored.ifBlank { null } } +
                    listOfNotNull(recipientArmoredKey.ifBlank { null })).distinct()
                val result = StandalonePgpEngine.decryptMulti(msg.ciphertext, keys.privateKeyArmored, candidateKeys)
                val wire = WireFormat.parse(result.bytes)

                // Handle Group Invites
                if (wire != null && wire.isGroupInvite && wire.groupId != null && wire.groupDropId != null) {
                    val newGroup = GroupChat(
                        id = wire.groupId,
                        name = wire.groupName ?: "Encrypted Group",
                        groupDropId = wire.groupDropId,
                        groupReadToken = wire.groupReadToken ?: "",
                        sharedKeyBase64 = wire.sharedKey ?: "",
                        members = emptyList()
                    )
                    if (!groups.any { it.id == newGroup.id }) {
                        runOnUiThread {
                            groups.add(newGroup)
                        }
                        torClient.addSubscription(newGroup.groupDropId, newGroup.groupReadToken)
                        persistIdentity()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Invited to group: ${newGroup.name} 👥", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                // Anti-replay check
                if (wire != null && wire.nonce != null) {
                    synchronized(seenNonces) {
                        if (seenNonces.contains(wire.nonce)) {
                            torClient.ack(listOf(msg.id), dropId = dropId)
                            return@launch
                        }
                        seenNonces.add(wire.nonce)
                        if (seenNonces.size > 2000) {
                            val first = seenNonces.iterator().next()
                            seenNonces.remove(first)
                        }
                    }
                }

                var isMedia = false
                var isAudio = false
                var mimeType = ""
                var filename = ""
                var previewBitmap: ImageBitmap? = null
                var audioBytes: ByteArray? = null
                var audioDurationMs = 0L
                var displayText: String? = null

                var isIntro = false
                var introAlias: String? = null
                var introDropId: String? = null
                var introArmoredKey: String? = null
                var introSafetyCode: String? = null

                if (wire != null && wire.isGroupInvite) {
                    val groupName = wire.groupName ?: "New Group"
                    displayText = "Group invitation: $groupName"
                    if (!wire.groupId.isNullOrBlank() && !wire.sharedKey.isNullOrBlank() && !wire.groupDropId.isNullOrBlank() && !wire.groupReadToken.isNullOrBlank()) {
                        val newGroup = GroupChat(
                            id = wire.groupId,
                            name = groupName,
                            groupDropId = wire.groupDropId,
                            groupReadToken = wire.groupReadToken,
                            sharedKeyBase64 = wire.sharedKey,
                            createdAt = System.currentTimeMillis()
                        )
                        if (!groups.any { it.id == newGroup.id || it.groupDropId == newGroup.groupDropId }) {
                            runOnUiThread {
                                groups.add(newGroup)
                                torClient.addSubscription(newGroup.groupDropId, newGroup.groupReadToken)
                                persistIdentity()
                            }
                        }
                    }
                } else if (wire != null && wire.isIntroduction) {
                    isIntro = true
                    introAlias = wire.introAlias
                    introDropId = wire.introDropId
                    introArmoredKey = wire.introArmoredKey
                    introSafetyCode = wire.introSafetyCode
                    displayText = "🤝 Introduced contact: ${wire.introAlias}"
                } else if (wire != null && wire.isMedia) {
                    isMedia = true
                    mimeType = wire.mimeType ?: ""
                    filename = wire.filename ?: ""
                    if (mimeType.startsWith("image/")) {
                        try {
                            val mediaBytes = WireFormat.decodeMediaData(wire)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(mediaBytes, 0, mediaBytes.size, bounds)
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_BUBBLE_IMAGE_EDGE)
                            }
                            BitmapFactory.decodeByteArray(mediaBytes, 0, mediaBytes.size, options)?.let {
                                previewBitmap = it.asImageBitmap()
                            }
                        } catch (_: Throwable) {}
                    } else if (mimeType.startsWith("audio/")) {
                        isAudio = true
                        try {
                            val rawBytes = WireFormat.decodeMediaData(wire)
                            audioBytes = rawBytes
                            val retriever = MediaMetadataRetriever()
                            val ds = MemoryAudioDataSource(rawBytes)
                            retriever.setDataSource(ds)
                            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            audioDurationMs = durStr?.toLongOrNull() ?: 0L
                            retriever.release()
                        } catch (_: Throwable) {}
                    }
                } else {
                    displayText = wire?.text ?: String(result.bytes, Charsets.UTF_8)
                }

                val senderFp = result.signerFingerprint
                val matchedContact = contacts.find { it.fingerprint == senderFp }
                val targetChat = matchedContact?.dropId ?: dropId
                val wireTtl = wire?.ttlSeconds
                val expires = if (wireTtl != null && wireTtl > 0) System.currentTimeMillis() + (wireTtl * 1000L) else null

                runOnUiThread {
                    val index = messagesState.indexOfFirst { it.id == msg.id }
                    if (index != -1) {
                        messagesState[index] = messagesState[index].copy(
                            plaintext = displayText,
                            isDecrypted = true,
                            isMedia = isMedia,
                            isAudio = isAudio,
                            mimeType = mimeType,
                            filename = filename,
                            mediaBitmap = previewBitmap,
                            audioBytes = audioBytes,
                            audioDurationMs = audioDurationMs,
                            signatureStatus = result.signatureStatus,
                            signerFingerprint = result.signerFingerprint,
                            chatId = targetChat,
                            senderAlias = wire?.senderAlias ?: matchedContact?.alias,
                            expiresAt = expires,
                            ttlSeconds = wireTtl,
                            isIntroduction = isIntro,
                            introAlias = introAlias,
                            introDropId = introDropId,
                            introArmoredKey = introArmoredKey,
                            introSafetyCode = introSafetyCode
                        )
                        torClient.ack(listOf(msg.id), dropId = dropId)
                    }
                    if (!isForeground) {
                        PushNotificationManager.postMessageNotification(
                            context = this@MainActivity,
                            isAudio = isAudio,
                            hasMedia = isMedia,
                            previewText = if (!isAudio && !isMedia) displayText else null
                        )
                    }
                }
            } catch (_: Exception) {
                if (!isForeground) {
                    runOnUiThread {
                        PushNotificationManager.postMessageNotification(
                            context = this@MainActivity,
                            isAudio = false,
                            hasMedia = false,
                            previewText = null
                        )
                    }
                }
            }
        }
    }

    private fun copyToClipboard(label: String, value: String) {
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

    private fun copyKeyToClipboard(keyText: String) {
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

    private fun setAppPin(pin: String?) {
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

    private fun setDuressPin(pin: String?) {
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

    private fun getDisappearingTtlForChat(chatId: String): Long? {
        return contacts.find { it.dropId == chatId }?.disappearingTtlSeconds
            ?: groups.find { it.id == chatId }?.let { null } // Groups don't have per-contact TTL yet
    }

    private fun setDisappearingTimerForContact(contactDropId: String, ttlSeconds: Long?) {
        val idx = contacts.indexOfFirst { it.dropId == contactDropId }
        if (idx >= 0) {
            contacts[idx] = contacts[idx].copy(disappearingTtlSeconds = ttlSeconds)
            persistIdentity()
        }
    }

    private fun triggerEmergencyPanicWipe() {
        try { persistDispatcher.cancel() } catch (_: Exception) {}
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

    private fun saveAllSettings(
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

    private fun onAutoSaveSettings(newUrl: String, newAlias: String) {
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

    private fun computeChatSummaries(): List<ChatSummary> {
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

    private fun sendMessageToActiveChat(text: String) {
        val group = activeChatGroup
        val contact = activeChatContact
        if (group != null) {
            sendGroupTextMessage(group, text)
        } else if (contact != null) {
            recipientArmoredKey = contact.publicKeyArmored
            recipientFingerprint = contact.fingerprint
            recipientDropId = contact.dropId
            sendMessage(text, targetChatId = contact.dropId)
        } else {
            sendMessage(text)
        }
    }

    private fun sendMediaToActiveChat(uri: Uri) {
        val group = activeChatGroup
        val contact = activeChatContact
        if (group != null) {
            sendGroupMedia(group, uri)
        } else if (contact != null) {
            recipientArmoredKey = contact.publicKeyArmored
            recipientFingerprint = contact.fingerprint
            recipientDropId = contact.dropId
            sendMedia(uri, targetChatId = contact.dropId)
        } else {
            sendMedia(uri)
        }
    }

    private fun sendGroupTextMessage(group: GroupChat, text: String) {
        val payload = WireFormat.buildText(text, senderAlias = userAlias, groupId = group.id)
        val ciphertext = GroupCrypto.encrypt(payload, group.sharedKeyBase64)
        sentCiphertexts.add(ciphertext)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val res = torClient.sendMessage(dropId = group.groupDropId, ciphertext = ciphertext)
                res.onSuccess {
                    runOnUiThread {
                        val msg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            isOutgoing = true,
                            plaintext = text,
                            ciphertext = ciphertext,
                            timestamp = System.currentTimeMillis(),
                            isDecrypted = true,
                            chatId = group.id,
                            groupId = group.id,
                            isGroup = true,
                            senderAlias = userAlias
                        )
                        messagesState.add(msg)
                    }
                }.onFailure { err ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to send to group: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Group encrypt error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendGroupMedia(group: GroupChat, uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cr = contentResolver
                val mimeType = cr.getType(uri) ?: "application/octet-stream"
                val filename = queryFilename(uri) ?: "attachment"
                val rawBytes = contentResolver.openInputStream(uri)?.use { readLimited(it, maxAttachmentBytes) } ?: return@launch
                val isAudio = mimeType.startsWith("audio/")
                val payload = WireFormat.buildMedia(mimeType, filename, rawBytes, senderAlias = userAlias, groupId = group.id)
                val ciphertext = GroupCrypto.encrypt(payload, group.sharedKeyBase64)
                sentCiphertexts.add(ciphertext)

                val res = torClient.sendMessage(dropId = group.groupDropId, ciphertext = ciphertext)
                res.onSuccess {
                    runOnUiThread {
                        val msg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            isOutgoing = true,
                            ciphertext = ciphertext,
                            timestamp = System.currentTimeMillis(),
                            isDecrypted = true,
                            isMedia = true,
                            isAudio = isAudio,
                            mimeType = mimeType,
                            filename = filename,
                            audioBytes = if (isAudio) rawBytes else null,
                            chatId = group.id,
                            groupId = group.id,
                            isGroup = true,
                            senderAlias = userAlias
                        )
                        messagesState.add(msg)
                    }
                }.onFailure { err ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Failed to send group media: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Media error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun inviteMembersToGroup(group: GroupChat) {
        val keys = myPgpKeys ?: return
        for (member in group.members) {
            val invitePayload = WireFormat.buildGroupInvite(
                groupId = group.id,
                groupName = group.name,
                groupDropId = group.groupDropId,
                groupReadToken = group.groupReadToken,
                sharedKey = group.sharedKeyBase64,
                senderAlias = userAlias
            )
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val ciphertext = StandalonePgpEngine.encrypt(invitePayload, member.publicKeyArmored, keys.privateKeyArmored)
                    torClient.sendMessage(dropId = member.dropId, ciphertext = ciphertext)
                } catch (_: Exception) {}
            }
        }
    }

    private fun onMicClicked() {
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

    private fun onStopAudioRecording() {
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

    private fun onCancelAudioRecording() {
        isRecordingAudio = false
        audioRecorderManager.cancelRecording()
    }

    private fun sendContactIntroduction(targetContact: Contact, contactToIntroduce: Contact) {
        val keys = myPgpKeys ?: return
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val wireBytes = WireFormat.buildIntroduction(
                    introAlias = contactToIntroduce.alias,
                    introDropId = contactToIntroduce.dropId,
                    introArmoredKey = contactToIntroduce.publicKeyArmored,
                    introSafetyCode = contactToIntroduce.safetyCode,
                    senderAlias = userAlias
                )
                val encryptedArmor = StandalonePgpEngine.encrypt(
                    wireBytes,
                    targetContact.publicKeyArmored,
                    keys.privateKeyArmored
                )
                val msgId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val outMsg = ChatMessage(
                    id = msgId,
                    isOutgoing = true,
                    plaintext = "🤝 Introduced ${contactToIntroduce.alias} to ${targetContact.alias}",
                    ciphertext = encryptedArmor,
                    timestamp = now,
                    isDecrypted = true,
                    chatId = targetContact.dropId,
                    isIntroduction = true,
                    introAlias = contactToIntroduce.alias,
                    introDropId = contactToIntroduce.dropId,
                    introArmoredKey = contactToIntroduce.publicKeyArmored,
                    introSafetyCode = contactToIntroduce.safetyCode
                )
                runOnUiThread {
                    messagesState.add(outMsg)
                }
                val deliveredLan = localLanMesh?.sendDirect(targetContact.dropId, encryptedArmor) == true
                if (!deliveredLan) {
                    torClient.sendMessage(dropId = targetContact.dropId, ciphertext = encryptedArmor)
                }
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Introduction sent to ${targetContact.alias} 🤝", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to introduce: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun addIntroducedContact(alias: String, dropId: String, armoredKey: String, safetyCode: String?) {
        val cleanDropId = dropId.trim().lowercase()
        if (!StandalonePgpEngine.isValidHex32(cleanDropId)) {
            Toast.makeText(this, "Invalid Drop-ID in contact introduction", Toast.LENGTH_SHORT).show()
            return
        }
        val info = try { StandalonePgpEngine.parsePeerKey(armoredKey) } catch (_: Exception) { null }
        if (info == null) {
            Toast.makeText(this, "Invalid PGP key in contact introduction", Toast.LENGTH_SHORT).show()
            return
        }
        if (info.dropId != null && info.dropId != cleanDropId) {
            Toast.makeText(this, "Security warning: Introduced Drop-ID does not match key!", Toast.LENGTH_LONG).show()
            return
        }
        if (contacts.any { it.dropId == cleanDropId || it.fingerprint == info.fingerprint }) {
            Toast.makeText(this, "Contact already exists in your address book", Toast.LENGTH_SHORT).show()
            return
        }
        val cleanAlias = alias.trim().replace(Regex("[\\r\\n\\t\\u202A-\\u202E\\u200E\\u200F<>]"), "").take(64)
        val verifiedSas = myPgpKeys?.let { StandalonePgpEngine.generateSafetyCode(it.fingerprint, info.fingerprint) }
        val isVerified = safetyCode != null && verifiedSas != null && safetyCode.replace(" ", "") == verifiedSas.replace(" ", "")
        val newContact = Contact(
            id = cleanDropId,
            alias = if (cleanAlias.isNotBlank()) cleanAlias else info.alias ?: "Peer",
            dropId = cleanDropId,
            publicKeyArmored = info.publicKeyArmored,
            fingerprint = info.fingerprint,
            isVerified = isVerified,
            safetyCode = verifiedSas
        )
        contacts.add(newContact)
        persistIdentity()
        Toast.makeText(this, "Added ${newContact.alias} to contacts 🛡️", Toast.LENGTH_SHORT).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainModernApp() {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var showAddDirectChatDialog by remember { mutableStateOf(false) }
        var showCreateGroupDialog by remember { mutableStateOf(false) }
        var showSafetyCodeDialog by remember { mutableStateOf(false) }
        var showNewIdentityConfirm by remember { mutableStateOf(false) }

        val playbackState by audioPlayerManager.playbackState.collectAsState()

        LaunchedEffect(isRecordingAudio) {
            if (isRecordingAudio) {
                val start = SystemClock.elapsedRealtime()
                while (isRecordingAudio) {
                    recordElapsedMs = SystemClock.elapsedRealtime() - start
                    delay(100)
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                TelegramDrawer(
                    userAlias = userAlias,
                    myPgpKeys = myPgpKeys,
                    relayStatus = relayStatus.value,
                    artiState = artiState,
                    onNavigateChats = {
                        currentNavScreen = AppNavScreen.CHATS_LIST
                        scope.launch { drawerState.close() }
                    },
                    onNavigateSettings = {
                        currentNavScreen = AppNavScreen.SETTINGS
                        scope.launch { drawerState.close() }
                    },
                    onCopyDropId = {
                        myPgpKeys?.dropId?.let { copyToClipboard("Drop ID", it) }
                    },
                    onCopyFingerprint = {
                        myPgpKeys?.fingerprint?.let { copyToClipboard("PGP Fingerprint", it) }
                    },
                    onCopyPublicKey = {
                        myPgpKeys?.publicKeyArmored?.let { copyToClipboard("Armored Public Key", it) }
                    },
                    onShowMyQr = {
                        showMyQrDialog = true
                        scope.launch { drawerState.close() }
                    },
                    onPairContact = {
                        showPairContactDialog = true
                        scope.launch { drawerState.close() }
                    },
                    onOpenSecurityDiagnostics = {
                        showSecurityDiagnostics = true
                        scope.launch { drawerState.close() }
                    },
                    onEmergencyPanic = {
                        triggerEmergencyPanicWipe()
                    }
                )
            }
        ) {
            BackHandler(enabled = drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
            BackHandler(enabled = currentNavScreen != AppNavScreen.CHATS_LIST && !drawerState.isOpen) {
                currentNavScreen = AppNavScreen.CHATS_LIST
            }

            when (currentNavScreen) {
                AppNavScreen.CHATS_LIST -> {
                    ChatsListScreen(
                        chats = computeChatSummaries(),
                        relayStatus = relayStatus.value,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onOpenSettings = { currentNavScreen = AppNavScreen.SETTINGS },
                        onSelectChat = { summary ->
                            activeChatContact = contacts.find { it.dropId == summary.chatId }
                            activeChatGroup = groups.find { it.id == summary.chatId }
                            currentNavScreen = AppNavScreen.CHAT_VIEW
                        },
                        onNewDirectChat = { showAddDirectChatDialog = true },
                        onNewGroupChat = { showCreateGroupDialog = true },
                        onShowMyQr = { showMyQrDialog = true },
                        onPairContactQr = { showPairContactDialog = true },
                        onOpenSecurityDiagnostics = { showSecurityDiagnostics = true }
                    )
                }
                AppNavScreen.CHAT_VIEW -> {
                    val title = activeChatGroup?.name ?: activeChatContact?.alias ?: "Encrypted Chat"
                    val subtitle = if (activeChatGroup != null) {
                        "${activeChatGroup?.members?.size ?: 1} members · AES-256 E2EE"
                    } else {
                        "Peer ${(activeChatContact?.fingerprint ?: "").take(8)} · E2EE PGP"
                    }
                    val activeChatId = activeChatGroup?.id ?: activeChatContact?.dropId ?: ""
                    val chatMessages = messagesState.filter { it.chatId == activeChatId || (activeChatId.isBlank() && it.chatId.isBlank()) }

                    CleanChatScreen(
                        chatTitle = title,
                        chatSubtitle = subtitle,
                        isGroup = activeChatGroup != null,
                        messages = chatMessages,
                        playbackState = playbackState,
                        relayStatus = relayStatus.value,
                        isRecordingAudio = isRecordingAudio,
                        recordElapsedMs = recordElapsedMs,
                        onBack = { currentNavScreen = AppNavScreen.CHATS_LIST },
                        onSendMessage = { text ->
                            sendMessageToActiveChat(text)
                        },
                        onSendMedia = { uri ->
                            sendMediaToActiveChat(uri)
                        },
                        onMicClick = { onMicClicked() },
                        onStopRecording = { onStopAudioRecording() },
                        onCancelRecording = { onCancelAudioRecording() },
                        onAudioPlayPause = { msg ->
                            if (msg.audioBytes != null) {
                                audioPlayerManager.play(msg.id, msg.audioBytes)
                            }
                        },
                        onAudioSeek = { msg, frac ->
                            audioPlayerManager.seekTo(msg.id, frac)
                        },
                        onManualDecrypt = { msg ->
                            decryptMessage(msg, msg.chatId.ifBlank { recipientDropId })
                        },
                        onOpenSafetyCode = { showSafetyCodeDialog = true },
                        onOpenSettings = { currentNavScreen = AppNavScreen.SETTINGS },
                        disappearingTtlSeconds = getDisappearingTtlForChat(activeChatId),
                        onSetDisappearingTimer = { ttl ->
                            activeChatContact?.let { setDisappearingTimerForContact(it.dropId, ttl) }
                        },
                        availableContacts = contacts.filter { it.dropId != activeChatContact?.dropId },
                        onIntroduceContact = { contactToIntroduce ->
                            activeChatContact?.let { target ->
                                sendContactIntroduction(target, contactToIntroduce)
                            }
                        },
                        onAcceptIntroduction = { alias, dropId, armoredKey, safetyCode ->
                            addIntroducedContact(alias, dropId, armoredKey, safetyCode)
                        }
                    )
                }
                AppNavScreen.SETTINGS -> {
                    SettingsScreen(
                        initialRelayUrl = relayServerUrl,
                        initialUseTorSocks = useTorSocks,
                        initialKeepAlive = keepAlive,
                        initialEphemeralMode = ephemeralMode,
                        initialLockEnabled = lockEnabled,
                        userAlias = userAlias,
                        myPgpKeys = myPgpKeys,
                        onBack = { currentNavScreen = AppNavScreen.CHATS_LIST },
                        onSaveSettings = { url, tor, keep, eph, lock, alias ->
                            saveAllSettings(url, tor, keep, eph, lock, alias)
                        },
                        onAutoSave = { url, alias ->
                            onAutoSaveSettings(url, alias)
                        },
                        onGenerateNewKey = { showNewIdentityConfirm = true },
                        onCopyKey = { myPgpKeys?.let { copyKeyToClipboard(it.publicKeyArmored) } },
                        onVerifySafetyCode = { showSafetyCodeDialog = true },
                        duressPinConfigured = !duressPinHash.isNullOrBlank(),
                        onSetDuressPin = { pin -> setDuressPin(pin) },
                        appPinConfigured = !appPinHash.isNullOrBlank(),
                        onSetAppPin = { pin -> setAppPin(pin) }
                    )
                }
            }
        }

        if (showAddDirectChatDialog) {
            AddDirectChatDialog(
                onDismiss = { showAddDirectChatDialog = false },
                onAddContact = { contact ->
                    showAddDirectChatDialog = false
                    if (!contacts.any { it.dropId == contact.dropId }) {
                        contacts.add(contact)
                        persistIdentity()
                    }
                    activeChatContact = contact
                    activeChatGroup = null
                    currentNavScreen = AppNavScreen.CHAT_VIEW
                }
            )
        }

        if (showCreateGroupDialog) {
            CreateGroupDialog(
                contacts = contacts.toList(),
                onDismiss = { showCreateGroupDialog = false },
                onCreateGroup = { group ->
                    showCreateGroupDialog = false
                    groups.add(group)
                    torClient.addSubscription(group.groupDropId, group.groupReadToken)
                    persistIdentity()
                    inviteMembersToGroup(group)
                    activeChatGroup = group
                    activeChatContact = null
                    currentNavScreen = AppNavScreen.CHAT_VIEW
                }
            )
        }

        if (showSafetyCodeDialog) {
            val myFp = myPgpKeys?.fingerprint ?: ""
            val peerFp = activeChatContact?.fingerprint ?: recipientFingerprint
            val activeContact = activeChatContact
            SafetyCodeDialog(
                safetyNumber = if (myPgpKeys != null && peerFp.isNotBlank()) {
                    StandalonePgpEngine.generateSafetyCode(myPgpKeys!!.fingerprint, peerFp)
                } else {
                    "NO PEER SELECTED"
                },
                recipientFingerprint = peerFp,
                isPeerVerified = activeContact?.isVerified ?: false,
                onVerificationChanged = { verified ->
                    if (activeContact != null) {
                        val idx = contacts.indexOfFirst { it.dropId == activeContact.dropId }
                        if (idx >= 0) {
                            contacts[idx] = contacts[idx].copy(isVerified = verified)
                            activeChatContact = contacts[idx]
                            persistIdentity()
                        }
                    }
                },
                onDismiss = { showSafetyCodeDialog = false }
            )
        }

        if (showSecurityDiagnostics) {
            SecurityStatusDialog(
                relayStatus = relayStatus.value,
                artiState = artiState,
                relayUrl = relayServerUrl,
                lanPeersCount = localLanMesh?.activePeersCount ?: 0,
                isEphemeral = ephemeralMode,
                hasDuressPin = !duressPinHash.isNullOrBlank(),
                myFingerprint = myPgpKeys?.fingerprint ?: "",
                myDropId = myPgpKeys?.dropId ?: "",
                onDismiss = { showSecurityDiagnostics = false }
            )
        }

        if (showNewIdentityConfirm) {
            NewIdentityConfirmDialog(
                onDismiss = { showNewIdentityConfirm = false },
                onConfirm = {
                    showNewIdentityConfirm = false
                    generateFreshKeys()
                }
            )
        }

        if (showMyQrDialog) {
            val keys = myPgpKeys
            if (keys != null) {
                MyQrCodeDialog(
                    myAlias = userAlias,
                    myDropId = keys.dropId,
                    myFingerprint = keys.fingerprint,
                    myPublicKeyArmored = keys.publicKeyArmored,
                    onDismiss = { showMyQrDialog = false }
                )
            } else {
                Toast.makeText(this, "Generating PGP identity, please wait...", Toast.LENGTH_SHORT).show()
                showMyQrDialog = false
            }
        }

        if (showPairContactDialog) {
            PairContactDialog(
                myFingerprint = myPgpKeys?.fingerprint ?: "",
                onDismiss = { showPairContactDialog = false },
                onContactVerified = { contact ->
                    showPairContactDialog = false
                    val existingIdx = contacts.indexOfFirst { it.dropId == contact.dropId }
                    if (existingIdx >= 0) {
                        contacts[existingIdx] = contact
                    } else {
                        contacts.add(contact)
                    }
                    persistIdentity()
                    activeChatContact = contact
                    activeChatGroup = null
                    currentNavScreen = AppNavScreen.CHAT_VIEW
                    Toast.makeText(this, "Contact paired & verified: ${contact.alias} 🛡️", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}
