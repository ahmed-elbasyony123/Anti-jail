package com.ghostkey.app

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.crypto.WireFormat
import com.ghostkey.app.data.ChatMessage
import com.ghostkey.app.data.PeerIdentity
import com.ghostkey.app.data.SecureStore
import com.ghostkey.app.data.StoredIdentity
import com.ghostkey.app.net.EncryptedMessageRecord
import com.ghostkey.app.net.OrbotPortWatcher
import com.ghostkey.app.net.RelayHttpException
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.net.TorRelayClient
import com.ghostkey.app.service.TorForegroundService
import com.ghostkey.app.ui.*
import com.ghostkey.app.ui.components.*
import com.ghostkey.app.ui.dialogs.*
import com.ghostkey.app.ui.screens.LockedScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
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

class MainActivity : ComponentActivity() {

    private val torClient = TorRelayClient()

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
    private var keyBarCollapsed by mutableStateOf(false)
    private val maxAttachmentBytes = 15 * 1024 * 1024
    private val seenNonces = Collections.synchronizedSet(LinkedHashSet<String>())

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
        useTorSocks = prefs.getBoolean("use_tor", relayServerUrl.contains(".onion"))
        keepAlive = prefs.getBoolean("keep_alive", false)
        orbotSocksPort = prefs.getInt("orbot_socks_port", OrbotPortWatcher.DEFAULT_SOCKS_PORT)
        ephemeralMode = prefs.getBoolean("ephemeral_mode", false)
        lockEnabled = prefs.getBoolean("lock_enabled", false)
        keyBarCollapsed = prefs.getBoolean("key_bar_collapsed", false)
        secureStore = SecureStore(applicationContext)

        // Initialize network client first, then bring up the identity
        applyNetworkConfig()
        if (lockEnabled && !ephemeralMode && secureStore.hasIdentity()) {
            isLocked = true
            requestUnlock()
        } else {
            bootstrapIdentity()
        }

        // Listen for the SOCKS port Orbot reports, if it does
        orbotWatcher = OrbotPortWatcher(this) { port -> runOnUiThread { onOrbotPort(port) } }.also { it.start() }
        if (keepAlive) TorForegroundService.start(this)

        setContent {
            GhostKeyTheme {
                if (isLocked) {
                    LockedScreen(onUnlock = { requestUnlock() })
                } else {
                    MainStealthScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orbotWatcher?.stop()
        torClient.disconnectWebSocket()
        if (isFinishing) TorForegroundService.stop(this)
    }

    private fun applyNetworkConfig() {
        torClient.updateConfig(relayServerUrl, useTorSocks, socksPort = orbotSocksPort)
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
        stored.peer?.let {
            recipientArmoredKey = it.publicKeyArmored
            recipientFingerprint = it.fingerprint
            recipientDropId = it.dropId
        }
        connectToRelayMailbox(stored.mine)
    }

    private fun persistIdentity() {
        if (ephemeralMode) return
        val mine = myPgpKeys ?: return
        val peer = if (recipientArmoredKey.isNotBlank()) {
            PeerIdentity(recipientArmoredKey, recipientFingerprint, recipientDropId)
        } else null
        val snapshot = StoredIdentity(mine, peer)
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
            editor.apply()
            CoroutineScope(persistDispatcher).launch { secureStore.wipe() }
        } else {
            editor.putString("relay_url", relayServerUrl).apply()
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

    private fun saveKeyBarPref() {
        getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("key_bar_collapsed", keyBarCollapsed).apply()
    }

    private fun generateFreshKeys() {
        isGeneratingKey = true
        StandalonePgpEngine.clearCache()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val keys = StandalonePgpEngine.generateKeyPair()
                runOnUiThread {
                    myPgpKeys = keys
                    isGeneratingKey = false
                    connectToRelayMailbox(keys)
                    persistIdentity()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isGeneratingKey = false
                    Toast.makeText(this@MainActivity, "Keygen failed: ${e.message}", Toast.LENGTH_LONG).show()
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
    }

    private fun handleIncoming(record: EncryptedMessageRecord) {
        val existing = messagesState.firstOrNull { it.id == record.id }
        if (existing != null) {
            if (existing.isDecrypted) torClient.ack(listOf(existing.id))
            return
        }
        val msg = ChatMessage(
            id = record.id,
            isOutgoing = false,
            plaintext = null,
            ciphertext = record.ciphertext,
            timestamp = record.createdAt,
            isDecrypted = false
        )
        messagesState.add(msg)
        decryptMessage(msg)
    }

    private fun sendMedia(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it, maxAttachmentBytes) }
                if (bytes == null) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Attachment is unreadable or larger than 15 MB",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val fileName = uri.lastPathSegment ?: "file"
                val payload = WireFormat.buildMedia(mimeType, fileName, bytes)

                var previewBitmap: ImageBitmap? = null
                if (mimeType.startsWith("image/")) {
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_BUBBLE_IMAGE_EDGE)
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let {
                            previewBitmap = it.asImageBitmap()
                        }
                    } catch (_: Throwable) {}
                }

                runOnUiThread {
                    sendPayload(
                        payload = payload,
                        isMedia = true,
                        mimeType = mimeType,
                        filename = fileName,
                        mediaBitmap = previewBitmap
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Attachment error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun readLimited(stream: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun sendMessage(text: String) {
        if (text.isBlank()) return
        sendPayload(payload = WireFormat.buildText(text), isMedia = false)
    }

    private fun sendPayload(
        payload: ByteArray,
        isMedia: Boolean = false,
        mimeType: String = "",
        filename: String = "",
        mediaBitmap: ImageBitmap? = null
    ) {
        val keys = myPgpKeys
        if (keys == null) {
            Toast.makeText(this, "No active PGP key in RAM. Please generate keys first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (recipientArmoredKey.isBlank()) {
            Toast.makeText(this, "Please paste your peer's public key first!", Toast.LENGTH_LONG).show()
            return
        }

        if (recipientDropId.isBlank()) {
            Toast.makeText(
                this,
                "Peer key has no Drop-ID. Ask your peer to copy their key again from GhostKey v2.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val peerKey = recipientArmoredKey
        val peerDrop = recipientDropId
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val ciphertext = StandalonePgpEngine.encrypt(payload, peerKey, keys.privateKeyArmored)
                val result = torClient.sendMessage(dropId = peerDrop, ciphertext = ciphertext)
                result.onSuccess {
                    runOnUiThread {
                        val msg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            isOutgoing = true,
                            plaintext = if (isMedia) null else String(payload, Charsets.UTF_8),
                            ciphertext = ciphertext,
                            timestamp = System.currentTimeMillis(),
                            isDecrypted = true,
                            isMedia = isMedia,
                            mimeType = mimeType,
                            filename = filename,
                            mediaBitmap = mediaBitmap
                        )
                        messagesState.add(msg)
                    }
                }.onFailure { err ->
                    runOnUiThread {
                        val reason = when ((err as? RelayHttpException)?.code) {
                            429 -> "rate limit reached or the peer's mailbox is full, try again shortly"
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

    private fun decryptMessage(msg: ChatMessage) {
        val keys = myPgpKeys ?: return
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val peerKey = recipientArmoredKey.ifBlank { null }
                val result = StandalonePgpEngine.decrypt(msg.ciphertext, keys.privateKeyArmored, peerKey)
                val wire = WireFormat.parse(result.bytes)

                // Anti-replay check: verify timestamp window and deduplicate nonce
                if (wire != null && wire.nonce != null) {
                    if (seenNonces.contains(wire.nonce)) {
                        torClient.ack(listOf(msg.id))
                        return@launch
                    }
                    seenNonces.add(wire.nonce)
                    if (seenNonces.size > 2000) {
                        val first = seenNonces.iterator().next()
                        seenNonces.remove(first)
                    }
                }

                var isMedia = false
                var mimeType = ""
                var filename = ""
                var previewBitmap: ImageBitmap? = null
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
                    }
                } else {
                    displayText = wire?.text ?: String(result.bytes, Charsets.UTF_8)
                }

                runOnUiThread {
                    val index = messagesState.indexOfFirst { it.id == msg.id }
                    if (index != -1) {
                        messagesState[index] = messagesState[index].copy(
                            plaintext = displayText,
                            isDecrypted = true,
                            isMedia = isMedia,
                            mimeType = mimeType,
                            filename = filename,
                            mediaBitmap = previewBitmap,
                            signatureStatus = result.signatureStatus,
                            signerFingerprint = result.signerFingerprint
                        )
                        torClient.ack(listOf(msg.id))
                    }
                }
            } catch (_: Exception) {
                // Keep as ciphertext for manual retry
            }
        }
    }

    private fun copyKeyToClipboard(keyText: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("", keyText))
        Toast.makeText(this, "Public Key copied! (auto-clears in 45s) 📋", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(45_000L)
            try {
                if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == keyText) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) {}
        }
    }

    private fun triggerEmergencyPanicWipe() {
        StandalonePgpEngine.clearCache()
        secureStore.wipe()
        getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {}
        seenNonces.clear()
        myPgpKeys = null
        recipientArmoredKey = ""
        recipientFingerprint = ""
        recipientDropId = ""
        messagesState.clear()
        torClient.disconnectWebSocket()
        TorForegroundService.stop(this)
        finishAffinity()
        exitProcess(0)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainStealthScreen() {
        var inputText by remember { mutableStateOf("") }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var showSafetyCodeDialog by remember { mutableStateOf(false) }
        var showPeerKeyDialog by remember { mutableStateOf(false) }
        var showMyKeyDialog by remember { mutableStateOf(false) }
        var isPeerVerified by remember { mutableStateOf(false) }
        var showKeySheet by remember { mutableStateOf(false) }
        var showNewIdentityConfirm by remember { mutableStateOf(false) }
        var showBurnConfirm by remember { mutableStateOf(false) }
        var showNewMessagesChip by remember { mutableStateOf(false) }

        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        fun nearBottom(): Boolean {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return true
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return true
            return last >= total - 2
        }

        LaunchedEffect(messagesState.size) {
            if (messagesState.isEmpty()) return@LaunchedEffect
            val newest = messagesState.last()
            if (newest.isOutgoing || nearBottom()) {
                listState.animateScrollToItem(messagesState.size - 1)
                showNewMessagesChip = false
            } else {
                showNewMessagesChip = true
            }
        }
        LaunchedEffect(listState) {
            snapshotFlow { nearBottom() }.collect { atBottom ->
                if (atBottom) showNewMessagesChip = false
            }
        }

        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        LaunchedEffect(imeVisible) {
            if (imeVisible && messagesState.isNotEmpty()) {
                listState.scrollToItem(messagesState.size - 1)
            }
        }

        val filePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                sendMedia(uri)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = StealthSurface,
                        titleContentColor = NeonEmerald
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "GHOSTKEY",
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.sp,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 18.sp,
                                color = NeonEmerald
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusBadge(
                                label = if (myPgpKeys != null) "PGP: READY" else if (isGeneratingKey) "PGP: GEN..." else "PGP: WIPED",
                                isActive = myPgpKeys != null
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            StatusBadge(
                                label = relayBadgeLabel(relayStatus.value),
                                isActive = relayStatus.value == RelayStatus.CONNECTED
                            )
                        }
                    },
                    actions = {
                        if (keyBarCollapsed && recipientArmoredKey.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(NeonEmerald.copy(alpha = 0.12f))
                                    .clickable { showKeySheet = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Shield,
                                        contentDescription = "Keys and identity",
                                        tint = NeonEmerald,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(NeonEmerald)
                                    )
                                }
                            }
                        } else {
                            IconButton(onClick = { showSafetyCodeDialog = true }) {
                                Icon(
                                    Icons.Default.VerifiedUser,
                                    contentDescription = "Safety Code",
                                    tint = if (isPeerVerified) NeonEmerald else StealthTextSecondary
                                )
                            }
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Security, contentDescription = "Settings", tint = StealthTextPrimary)
                        }
                        IconButton(
                            onClick = { triggerEmergencyPanicWipe() },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(AlertCrimson.copy(alpha = 0.2f))
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = "Panic Wipe", tint = AlertCrimson)
                        }
                    }
                )
            },
            containerColor = StealthBackground
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (relayStatus.value != RelayStatus.CONNECTED) {
                    RelayStatusBanner(relayStatus.value)
                }

                if (!keyBarCollapsed || recipientArmoredKey.isBlank()) {
                    KeyBar(
                        myPgpKeys = myPgpKeys,
                        recipientArmoredKey = recipientArmoredKey,
                        recipientFingerprint = recipientFingerprint,
                        onOpenMyKey = { showMyKeyDialog = true },
                        onCopyMyKey = { myPgpKeys?.let { copyKeyToClipboard(it.publicKeyArmored) } },
                        onNewKey = { showNewIdentityConfirm = true },
                        onHideKeyBar = {
                            keyBarCollapsed = true
                            saveKeyBarPref()
                        },
                        onOpenPeerKey = { showPeerKeyDialog = true }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (messagesState.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = StealthTextSecondary,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            "No messages in RAM memory.",
                                            color = StealthTextSecondary,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            "Messages are kept in memory only and are gone when the app closes.",
                                            color = StealthTextSecondary.copy(alpha = 0.6f),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        items(messagesState, key = { it.id }) { msg ->
                            MessageBubble(
                                message = msg,
                                onToggleArmor = {
                                    val idx = messagesState.indexOfFirst { it.id == msg.id }
                                    if (idx != -1) {
                                        messagesState[idx] = messagesState[idx].copy(
                                            showRawArmor = !messagesState[idx].showRawArmor
                                        )
                                    }
                                },
                                onManualDecrypt = { decryptMessage(msg) }
                            )
                        }
                    }

                    if (showNewMessagesChip) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(NeonEmerald)
                            .clickable {
                                showNewMessagesChip = false
                                scope.launch {
                                    if (messagesState.isNotEmpty()) listState.animateScrollToItem(messagesState.size - 1)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            "New messages ↓",
                            color = Color(0xFF002914),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StealthSurface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { filePicker.launch("*/*") },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(StealthSurfaceVariant)
                    ) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = "Attach File",
                            tint = NeonEmerald
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                if (recipientArmoredKey.isBlank()) "Paste peer key above first..." else "Type secret message...",
                                color = StealthTextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = StealthSurfaceVariant,
                            unfocusedContainerColor = StealthSurfaceVariant,
                            focusedTextColor = StealthTextPrimary,
                            unfocusedTextColor = StealthTextPrimary,
                            cursorColor = NeonEmerald,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp)),
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (recipientArmoredKey.isBlank()) {
                                showPeerKeyDialog = true
                                Toast.makeText(this@MainActivity, "Please set recipient key first", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            val txt = inputText
                            inputText = ""
                            sendMessage(txt)
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(NeonEmerald)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Encrypted",
                            tint = Color(0xFF002914)
                        )
                    }
                }
            }

            if (showKeySheet) {
                KeyBottomSheet(
                    recipientFingerprint = recipientFingerprint,
                    onDismiss = { showKeySheet = false },
                    onCopyMyKey = {
                        myPgpKeys?.let { copyKeyToClipboard(it.publicKeyArmored) }
                        showKeySheet = false
                    },
                    onEditPeerKey = {
                        showKeySheet = false
                        showPeerKeyDialog = true
                    },
                    onOpenSafetyCode = {
                        showKeySheet = false
                        showSafetyCodeDialog = true
                    },
                    onNewIdentity = {
                        showKeySheet = false
                        showNewIdentityConfirm = true
                    },
                    onBurnIdentity = {
                        showKeySheet = false
                        showBurnConfirm = true
                    }
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

            if (showBurnConfirm) {
                BurnConfirmDialog(
                    onDismiss = { showBurnConfirm = false },
                    onConfirm = { triggerEmergencyPanicWipe() }
                )
            }

            if (showMyKeyDialog && myPgpKeys != null) {
                MyKeyDialog(
                    myPgpKeys = myPgpKeys!!,
                    onDismiss = { showMyKeyDialog = false },
                    onCopyKey = {
                        myPgpKeys?.let { copyKeyToClipboard(it.publicKeyArmored) }
                        showMyKeyDialog = false
                    }
                )
            }

            if (showPeerKeyDialog) {
                PeerKeyDialog(
                    initialKeyText = recipientArmoredKey,
                    onDismiss = { showPeerKeyDialog = false },
                    onSaveKey = { keyText ->
                        try {
                            val info = StandalonePgpEngine.parsePeerKey(keyText)
                            recipientArmoredKey = info.publicKeyArmored
                            recipientFingerprint = info.fingerprint
                            recipientDropId = info.dropId ?: ""
                            isPeerVerified = false
                            keyBarCollapsed = true
                            saveKeyBarPref()
                            persistIdentity()
                            showPeerKeyDialog = false
                            val note = if (info.dropId == null) " (no Drop-ID found in this key)" else ""
                            Toast.makeText(
                                this@MainActivity,
                                "Key saved. Fingerprint: ${info.fingerprint.take(12)}...$note",
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Invalid PGP Key: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            if (showSettingsDialog) {
                SettingsDialog(
                    initialRelayUrl = relayServerUrl,
                    initialUseTorSocks = useTorSocks,
                    initialKeepAlive = keepAlive,
                    initialEphemeralMode = ephemeralMode,
                    initialLockEnabled = lockEnabled,
                    onDismiss = { showSettingsDialog = false },
                    onSave = { newUrl, newTor, newKeepAlive, newEphemeral, newLock ->
                        showSettingsDialog = false
                        var cleanUrl = newUrl.trim().trimEnd('/')
                        if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                            cleanUrl = "http://$cleanUrl"
                        }
                        relayServerUrl = cleanUrl
                        useTorSocks = newTor
                        updateKeepAlive(newKeepAlive)
                        updateEphemeralMode(newEphemeral)
                        updateLockEnabled(newLock)

                        val p = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE)
                        val editor = p.edit()
                        if (!newEphemeral) {
                            editor.putString("relay_url", relayServerUrl)
                        } else {
                            editor.remove("relay_url")
                        }
                        editor.putBoolean("use_tor", useTorSocks).apply()
                        applyNetworkConfig()
                        myPgpKeys?.let { connectToRelayMailbox(it) }
                    }
                )
            }

            if (showSafetyCodeDialog) {
                val safetyNumber = if (myPgpKeys != null && recipientFingerprint.isNotBlank()) {
                    StandalonePgpEngine.generateSafetyCode(myPgpKeys!!.fingerprint, recipientFingerprint)
                } else {
                    "PLEASE PASTE PEER KEY FIRST"
                }
                SafetyCodeDialog(
                    safetyNumber = safetyNumber,
                    recipientFingerprint = recipientFingerprint,
                    isPeerVerified = isPeerVerified,
                    onVerificationChanged = { isPeerVerified = it },
                    onDismiss = { showSafetyCodeDialog = false }
                )
            }
        }
    }

    private fun relayBadgeLabel(status: RelayStatus): String = when (status) {
        RelayStatus.CONNECTED -> "RELAY: LIVE"
        RelayStatus.BUILDING_CIRCUIT -> "TOR: CONNECTING"
        RelayStatus.ORBOT_NOT_RUNNING -> "NO ORBOT"
        RelayStatus.TIMED_OUT -> "TIMED OUT"
        RelayStatus.AUTH_FAILED -> "AUTH FAILED"
        RelayStatus.DISCONNECTED -> "RELAY: OFF"
    }
}
