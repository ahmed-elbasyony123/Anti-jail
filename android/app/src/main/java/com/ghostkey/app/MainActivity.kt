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
    private var relayServerUrl by mutableStateOf("http://10.0.2.2:8765")
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

    // Encrypted PTT Audio Voice Notes (Opus in RAM)
    private lateinit var audioRecorderManager: AudioRecorderManager
    private val audioPlayerManager = AudioPlayerManager()
    private var isRecordingAudio by mutableStateOf(false)
    private var recordElapsedMs by mutableStateOf(0L)

    // Embedded Arti Tor Engine (Rust) State
    private var artiState by mutableStateOf<ArtiTorState>(ArtiTorState.Idle)

    // Foreground / Background Tracking for Tor Push Notifications
    private var isForeground = false

    override fun onResume() {
        super.onResume()
        isForeground = true
        PushNotificationManager.clearMessageNotifications(this)
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
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
        relayServerUrl = prefs.getString("relay_url", "http://10.0.2.2:8765") ?: "http://10.0.2.2:8765"
        useTorSocks = prefs.getBoolean("use_tor", true)
        keepAlive = prefs.getBoolean("keep_alive", true)
        orbotSocksPort = prefs.getInt("orbot_socks_port", OrbotPortWatcher.DEFAULT_SOCKS_PORT)
        ephemeralMode = prefs.getBoolean("ephemeral_mode", false)
        lockEnabled = prefs.getBoolean("lock_enabled", false)
        userAlias = prefs.getString("user_alias", "") ?: ""

        secureStore = SecureStore(applicationContext)
        audioRecorderManager = AudioRecorderManager(applicationContext)

        // Initialize network client first, then bring up the identity
        applyNetworkConfig()
        if (lockEnabled && !ephemeralMode && secureStore.hasIdentity()) {
            isLocked = true
            requestUnlock()
        } else {
            bootstrapIdentity()
        }

        // Initialize Embedded Arti Tor (Rust engine) or fallback to external Orbot
        initTorNetworkEngine()
        PushNotificationManager.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }
        if (keepAlive) TorForegroundService.start(this)

        setContent {
            GhostKeyTheme {
                if (isLocked) {
                    LockedScreen(onUnlock = { requestUnlock() })
                } else {
                    MainModernApp()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
                            if (state.socksPort > 0) {
                                onOrbotPort(state.socksPort)
                            }
                        }
                        is ArtiTorState.Connected -> {
                            onOrbotPort(state.socksPort)
                        }
                        is ArtiTorState.Error -> {
                            startOrbotWatcher()
                        }
                        is ArtiTorState.Idle -> {}
                    }
                }
            }
            if (useTorSocks || relayServerUrl.contains(".onion")) {
                ArtiTorManager.start(applicationContext)
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
        if ((useTorSocks || relayServerUrl.contains(".onion")) && ArtiTorManager.isAvailable) {
            ArtiTorManager.start(applicationContext)
        }
    }

    private fun onOrbotPort(port: Int) {
        if (orbotSocksPort != port) {
            orbotSocksPort = port
            getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit()
                .putInt("orbot_socks_port", port).apply()
            applyNetworkConfig()
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
            lockEnabled = false
            getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit().putBoolean("lock_enabled", false).apply()
            isLocked = false
            Toast.makeText(this, "No device screen lock is set, so the app lock was turned off", Toast.LENGTH_LONG).show()
            bootstrapIdentity()
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
        userAlias = stored.userAlias
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

        connectToRelayMailbox(stored.mine)
        for (g in groups) {
            torClient.addSubscription(g.groupDropId, g.groupReadToken)
        }
    }

    private fun persistIdentity() {
        if (ephemeralMode) return
        val mine = myPgpKeys ?: return
        val peer = if (recipientArmoredKey.isNotBlank()) {
            PeerIdentity(recipientArmoredKey, recipientFingerprint, recipientDropId)
        } else null
        val snapshot = StoredIdentity(
            mine = mine,
            peer = peer,
            userAlias = userAlias,
            contacts = contacts.toList(),
            groups = groups.toList()
        )
        CoroutineScope(persistDispatcher).launch {
            try {
                secureStore.save(snapshot)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Could not save identity: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateEphemeralMode(enabled: Boolean) {
        ephemeralMode = enabled
        val editor = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit()
        editor.putBoolean("ephemeral_mode", enabled)
        if (enabled) {
            editor.remove("relay_url")
            editor.remove("user_alias")
            editor.apply()
            CoroutineScope(persistDispatcher).launch { secureStore.wipe() }
        } else {
            editor.putString("relay_url", relayServerUrl)
            editor.putString("user_alias", userAlias)
            editor.apply()
            persistIdentity()
        }
    }

    private fun updateLockEnabled(enabled: Boolean) {
        if (enabled) {
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguard.isDeviceSecure) {
                Toast.makeText(
                    this,
                    "Set a screen lock (PIN, pattern or password) in Android settings first",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }
        lockEnabled = enabled
        getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit().putBoolean("lock_enabled", enabled).apply()
    }

    private fun generateFreshKeys() {
        isGeneratingKey = true
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val pair = StandalonePgpEngine.generateKeyPair(userAlias.ifBlank { null })
                runOnUiThread {
                    myPgpKeys = pair
                    isGeneratingKey = false
                    messagesState.clear()
                    persistIdentity()
                    connectToRelayMailbox(pair)
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
        sendPayload(payload = WireFormat.buildText(text, senderAlias = userAlias), isMedia = false, displayText = text, targetChatId = targetChatId)
    }

    private fun sendAudioRecordResult(result: AudioRecordResult, targetChatId: String = "") {
        val payload = WireFormat.buildMedia(result.mimeType, result.filename, result.bytes, senderAlias = userAlias)
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
                val result = torClient.sendMessage(dropId = peerDrop, ciphertext = ciphertext)
                result.onSuccess {
                    runOnUiThread {
                        val cleanText = if (isMedia) null else (displayText ?: WireFormat.parse(payload)?.text ?: String(payload, Charsets.UTF_8))
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
                            senderAlias = userAlias
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
                    runOnUiThread {
                        val index = messagesState.indexOfFirst { it.id == msg.id }
                        if (index != -1) {
                            messagesState[index] = messagesState[index].copy(
                                plaintext = wire?.text ?: String(plainBytes, Charsets.UTF_8),
                                isDecrypted = true,
                                chatId = group.id,
                                groupId = group.id,
                                isGroup = true,
                                senderAlias = wire?.senderAlias
                            )
                            torClient.ack(listOf(msg.id), dropId = group.groupDropId)
                        }
                    }
                } catch (_: Exception) {}
            }
            return
        }

        // Direct 1-to-1 PGP message
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val peerKey = recipientArmoredKey.ifBlank { null }
                val result = StandalonePgpEngine.decrypt(msg.ciphertext, keys.privateKeyArmored, peerKey)
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
                        groups.add(newGroup)
                        torClient.addSubscription(newGroup.groupDropId, newGroup.groupReadToken)
                        persistIdentity()
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Invited to group: ${newGroup.name} 👥", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                // Anti-replay check
                if (wire != null && wire.nonce != null) {
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

                var isMedia = false
                var isAudio = false
                var mimeType = ""
                var filename = ""
                var previewBitmap: ImageBitmap? = null
                var audioBytes: ByteArray? = null
                var audioDurationMs = 0L
                var displayText: String? = null

                if (wire != null && wire.isMedia) {
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
                            senderAlias = wire?.senderAlias ?: matchedContact?.alias
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

    private fun triggerEmergencyPanicWipe() {
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
        myPgpKeys = null
        contacts.clear()
        groups.clear()
        userAlias = ""
        recipientArmoredKey = ""
        recipientFingerprint = ""
        recipientDropId = ""
        messagesState.clear()
        torClient.disconnectWebSocket()
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
            cleanUrl = "http://$cleanUrl"
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
        editor.putBoolean("use_tor", useTorSocks).apply()
        applyNetworkConfig()
        persistIdentity()
        myPgpKeys?.let { connectToRelayMailbox(it) }
    }

    private fun computeChatSummaries(): List<ChatSummary> {
        val list = mutableListOf<ChatSummary>()
        for (g in groups) {
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
            val lastMsg = messagesState.filter { it.chatId == c.dropId }.lastOrNull()
            list.add(
                ChatSummary(
                    chatId = c.dropId,
                    title = c.alias,
                    subtitle = "Peer ${c.fingerprint.take(8)}",
                    isGroup = false,
                    lastMessageText = lastMsg?.plaintext ?: if (lastMsg?.isAudio == true) "🎙️ Voice note" else if (lastMsg?.isMedia == true) "📎 Attachment" else "Direct E2EE session",
                    lastMessageTime = lastMsg?.timestamp ?: c.addedAt,
                    avatarLetter = c.alias.take(1)
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
        val cr = contentResolver
        val mimeType = cr.getType(uri) ?: "application/octet-stream"
        val filename = queryFilename(uri) ?: "attachment"
        val rawBytes = contentResolver.openInputStream(uri)?.use { readLimited(it, maxAttachmentBytes) } ?: return
        val isAudio = mimeType.startsWith("audio/")
        val payload = WireFormat.buildMedia(mimeType, filename, rawBytes, senderAlias = userAlias, groupId = group.id)
        val ciphertext = GroupCrypto.encrypt(payload, group.sharedKeyBase64)
        CoroutineScope(Dispatchers.Default).launch {
            try {
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
                }
            } catch (_: Exception) {}
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
                    onEmergencyPanic = {
                        triggerEmergencyPanicWipe()
                    }
                )
            }
        ) {
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
                        onNewGroupChat = { showCreateGroupDialog = true }
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
                            if (playbackState.activeMessageId == msg.id && playbackState.isPlaying) {
                                audioPlayerManager.pause()
                            } else if (msg.audioBytes != null) {
                                audioPlayerManager.play(msg.id, msg.audioBytes)
                            }
                        },
                        onAudioSeek = { msg, frac ->
                            if (playbackState.activeMessageId == msg.id) {
                                audioPlayerManager.seekToFraction(frac)
                            }
                        },
                        onOpenSafetyCode = { showSafetyCodeDialog = true },
                        onOpenSettings = { currentNavScreen = AppNavScreen.SETTINGS }
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
                        onGenerateNewKey = { showNewIdentityConfirm = true },
                        onCopyKey = { myPgpKeys?.let { copyKeyToClipboard(it.publicKeyArmored) } },
                        onVerifySafetyCode = { showSafetyCodeDialog = true }
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
            SafetyCodeDialog(
                safetyNumber = if (myPgpKeys != null && peerFp.isNotBlank()) {
                    StandalonePgpEngine.generateSafetyCode(myPgpKeys!!.fingerprint, peerFp)
                } else {
                    "NO PEER SELECTED"
                },
                recipientFingerprint = peerFp,
                isPeerVerified = false,
                onVerificationChanged = {},
                onDismiss = { showSafetyCodeDialog = false }
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
    }
}
