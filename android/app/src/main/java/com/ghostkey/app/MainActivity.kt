package com.ghostkey.app

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.crypto.SignatureStatus
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.crypto.WireFormat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

data class ChatMessage(
    val id: String,
    val isOutgoing: Boolean,
    var plaintext: String?,
    val ciphertext: String,
    val timestamp: Long,
    var isDecrypted: Boolean = false,
    var showRawArmor: Boolean = false,
    val signatureStatus: SignatureStatus? = null,
    val signerFingerprint: String? = null
)

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

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the service runs either way */ }

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
                if (isLocked) LockedScreen() else MainStealthScreen()
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
        if (port == orbotSocksPort) return
        orbotSocksPort = port
        getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit().putInt("orbot_socks_port", port).apply()
        applyNetworkConfig()
    }

    private fun updateKeepAlive(enabled: Boolean) {
        keepAlive = enabled
        getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit().putBoolean("keep_alive", enabled).apply()
        if (enabled) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
            // The device no longer has a secure lock screen, so the lock cannot be enforced.
            lockEnabled = false
            getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit().putBoolean("lock_enabled", false).apply()
            isLocked = false
            Toast.makeText(this, "No device screen lock is set, so the app lock was turned off", Toast.LENGTH_LONG).show()
            bootstrapIdentity()
        } else {
            unlockLauncher.launch(intent)
        }
    }

    /** Load the saved identity if there is one, otherwise create (and save) a new one. */
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

    /** Save the current identity (and peer) encrypted on disk. Does nothing in ephemeral mode. */
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
            // Anti-forensic protection (loophole P3): erase relay URL from disk so it remains RAM-only
            editor.remove("relay_url")
            editor.apply()
            // Nothing may stay on disk: remove the saved identity and its keystore key.
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

    /** The relay delivers at least once, so the same message id can arrive more than once. */
    private fun handleIncoming(record: EncryptedMessageRecord) {
        val existing = messagesState.firstOrNull { it.id == record.id }
        if (existing != null) {
            if (existing.isDecrypted) torClient.ack(listOf(existing.id))   // earlier ACK may have been lost
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

    /** File reading and Base64/JSON building run on the IO dispatcher, never on the main thread. */
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
                runOnUiThread { sendPayload(payload) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Attachment error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Reads at most [maxBytes]; returns null if the stream is larger. */
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
        sendPayload(WireFormat.buildText(text))
    }

    private fun sendPayload(payload: ByteArray) {
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
                // Step 1: Sign with our key and encrypt for the peer (in memory)
                val ciphertext = StandalonePgpEngine.encrypt(payload, peerKey, keys.privateKeyArmored)

                // Step 2: Push to the peer's blind drop
                val result = torClient.sendMessage(
                    dropId = peerDrop,
                    ciphertext = ciphertext
                )
                result.onSuccess {
                    runOnUiThread {
                        val msg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            isOutgoing = true,
                            plaintext = String(payload, Charsets.UTF_8),
                            ciphertext = ciphertext,
                            timestamp = System.currentTimeMillis(),
                            isDecrypted = true
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
                val plainText = String(result.bytes, Charsets.UTF_8)
                val wire = WireFormat.parse(result.bytes)

                // Anti-replay check (loophole P2): verify timestamp window and deduplicate nonce
                if (wire != null && wire.nonce != null) {
                    if (seenNonces.contains(wire.nonce)) {
                        // Replay attack: old ciphertext re-submitted to the relay
                        torClient.ack(listOf(msg.id))
                        return@launch
                    }
                    seenNonces.add(wire.nonce)
                    if (seenNonces.size > 2000) {
                        val first = seenNonces.iterator().next()
                        seenNonces.remove(first)
                    }
                }

                runOnUiThread {
                    val index = messagesState.indexOfFirst { it.id == msg.id }
                    if (index != -1) {
                        messagesState[index] = messagesState[index].copy(
                            plaintext = plainText,
                            isDecrypted = true,
                            signatureStatus = result.signatureStatus,
                            signerFingerprint = result.signerFingerprint
                        )
                        // Only now that the message is decrypted and in state may the relay delete it.
                        torClient.ack(listOf(msg.id))
                    }
                }
            } catch (e: Exception) {
                // Keep as ciphertext for manual retry
            }
        }
    }

    /**
     * Copy public key with automatic clipboard clearing after 45 seconds (loophole N3).
     */
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

    /**
     * EMERGENCY PANIC WIPE
     * Erases all memory objects, clears lists, closes sockets, wipes clipboard and terminates immediately.
     */
    private fun triggerEmergencyPanicWipe() {
        // Crypto-erase: delete the keystore key and the encrypted identity file, then all settings.
        secureStore.wipe()
        getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        // Issue N6: clear system clipboard so public keys/chat text don't remain
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

        // True when the newest message is on screen or close to it (within the last 2 items).
        fun nearBottom(): Boolean {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return true
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return true
            return last >= total - 2
        }

        // Follow new messages only if the reader is already at the bottom (or sent the message).
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

        // Keep the newest message visible when the soft keyboard opens.
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        LaunchedEffect(imeVisible) {
            if (imeVisible && messagesState.isNotEmpty()) {
                listState.scrollToItem(messagesState.size - 1)
            }
        }

        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

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
                            // Status indicators
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
                            // Compact shield chip: keys, safety code and identity actions live in a sheet
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
                            // Safety Code Verification Button
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
                        // Panic Wipe Button
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

                // Key Management Quick Cards (collapsed into the shield chip once the peer key is saved)
                if (!keyBarCollapsed || recipientArmoredKey.isBlank()) Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StealthSurfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // My Key Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showMyKeyDialog = true }
                        ) {
                            Icon(Icons.Default.VpnKey, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (myPgpKeys != null) "MY KEY: ${myPgpKeys!!.fingerprint.take(12)}..." else "NO KEY IN RAM",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (myPgpKeys != null) NeonEmerald else AlertCrimson
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    myPgpKeys?.let { copyKeyToClipboard(it.publicKeyArmored) }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("COPY KEY", fontSize = 10.sp, color = NeonEmerald, fontFamily = FontFamily.Monospace)
                            }
                            TextButton(
                                onClick = { showNewIdentityConfirm = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("NEW KEY", fontSize = 10.sp, color = StealthTextSecondary, fontFamily = FontFamily.Monospace)
                            }
                            if (recipientArmoredKey.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        keyBarCollapsed = true
                                        saveKeyBarPref()
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("HIDE", fontSize = 10.sp, color = StealthTextSecondary, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    // Recipient Key Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (recipientArmoredKey.isBlank()) AlertCrimson.copy(alpha = 0.15f) else NeonEmerald.copy(alpha = 0.1f))
                            .border(
                                1.dp,
                                if (recipientArmoredKey.isBlank()) AlertCrimson.copy(alpha = 0.4f) else NeonEmerald.copy(alpha = 0.4f),
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { showPeerKeyDialog = true }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (recipientArmoredKey.isBlank()) Icons.Default.AddLink else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (recipientArmoredKey.isBlank()) AlertCrimson else NeonEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (recipientArmoredKey.isNotBlank()) "PEER: ${recipientFingerprint.take(12)}..." else "PASTE PEER'S PUBLIC KEY TO CHAT",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (recipientArmoredKey.isNotBlank()) NeonEmerald else AlertCrimson
                            )
                        }

                        Text(
                            text = if (recipientArmoredKey.isNotBlank()) "EDIT" else "PASTE",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (recipientArmoredKey.isNotBlank()) NeonEmerald else AlertCrimson
                        )
                    }
                }

                // Chat Message Stream
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

                // Input Bar
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
                ModalBottomSheet(
                    onDismissRequest = { showKeySheet = false },
                    containerColor = StealthSurface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "KEYS & IDENTITY",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeonEmerald,
                            fontSize = 13.sp
                        )
                        Text(
                            "PEER: ${recipientFingerprint.take(12)}...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = StealthTextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SheetAction(Icons.Default.ContentCopy, "COPY MY KEY (includes Drop-ID)", NeonEmerald) {
                            myPgpKeys?.let { copyKeyToClipboard(it.publicKeyArmored) }
                            showKeySheet = false
                        }
                        SheetAction(Icons.Default.Edit, "PASTE / EDIT PEER KEY", NeonEmerald) {
                            showKeySheet = false
                            showPeerKeyDialog = true
                        }
                        SheetAction(Icons.Default.VerifiedUser, "SAFETY CODE", NeonEmerald) {
                            showKeySheet = false
                            showSafetyCodeDialog = true
                        }
                        SheetAction(Icons.Default.Refresh, "NEW IDENTITY", WarningAmber) {
                            showKeySheet = false
                            showNewIdentityConfirm = true
                        }
                        SheetAction(Icons.Default.DeleteForever, "BURN IDENTITY", AlertCrimson) {
                            showKeySheet = false
                            showBurnConfirm = true
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }

            if (showNewIdentityConfirm) {
                AlertDialog(
                    onDismissRequest = { showNewIdentityConfirm = false },
                    containerColor = StealthSurface,
                    title = {
                        Text("NEW IDENTITY?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = WarningAmber, fontSize = 15.sp)
                    },
                    text = {
                        Text(
                            "This replaces your keys and Drop-ID. Your peer needs your new public key to keep chatting with you.",
                            color = StealthTextSecondary,
                            fontSize = 12.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showNewIdentityConfirm = false
                                generateFreshKeys()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                        ) { Text("CREATE", color = Color(0xFF002914), fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNewIdentityConfirm = false }) { Text("CANCEL", color = StealthTextSecondary) }
                    }
                )
            }

            if (showBurnConfirm) {
                AlertDialog(
                    onDismissRequest = { showBurnConfirm = false },
                    containerColor = StealthSurface,
                    title = {
                        Text("BURN IDENTITY?", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = AlertCrimson, fontSize = 15.sp)
                    },
                    text = {
                        Text(
                            "Deletes the saved identity and its encryption key, clears all settings and closes the app. This cannot be undone.",
                            color = StealthTextSecondary,
                            fontSize = 12.sp
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { triggerEmergencyPanicWipe() },
                            colors = ButtonDefaults.buttonColors(containerColor = AlertCrimson)
                        ) { Text("BURN", color = Color.White, fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBurnConfirm = false }) { Text("CANCEL", color = StealthTextSecondary) }
                    }
                )
            }

            // Dialog: My Public Key Full View & Share
            if (showMyKeyDialog && myPgpKeys != null) {
                AlertDialog(
                    onDismissRequest = { showMyKeyDialog = false },
                    containerColor = StealthSurface,
                    title = {
                        Text(
                            "MY OPENPGP PUBLIC KEY",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeonEmerald,
                            fontSize = 15.sp
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Fingerprint:\n${myPgpKeys!!.fingerprint}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = StealthTextSecondary
                            )
                            OutlinedTextField(
                                value = myPgpKeys!!.publicKeyArmored,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                myPgpKeys?.let { copyKeyToClipboard(it.publicKeyArmored) }
                                showMyKeyDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                        ) {
                            Text("COPY KEY", color = Color(0xFF002914), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showMyKeyDialog = false }) {
                            Text("CLOSE", color = StealthTextSecondary)
                        }
                    }
                )
            }

            // Dialog: Paste Peer Public Key
            if (showPeerKeyDialog) {
                var tempKeyText by remember { mutableStateOf(recipientArmoredKey) }
                AlertDialog(
                    onDismissRequest = { showPeerKeyDialog = false },
                    containerColor = StealthSurface,
                    title = {
                        Text(
                            "RECIPIENT PUBLIC KEY",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeonEmerald,
                            fontSize = 15.sp
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Paste your peer's armored OpenPGP public key (starts with -----BEGIN PGP PUBLIC KEY BLOCK-----):",
                                color = StealthTextSecondary,
                                fontSize = 11.sp
                            )
                            OutlinedTextField(
                                value = tempKeyText,
                                onValueChange = { tempKeyText = it },
                                placeholder = { Text("-----BEGIN PGP PUBLIC KEY BLOCK-----\n...", fontSize = 10.sp) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                try {
                                    val info = StandalonePgpEngine.parsePeerKey(tempKeyText)
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
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                        ) {
                            Text("SAVE KEY", color = Color(0xFF002914), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPeerKeyDialog = false }) {
                            Text("CANCEL", color = StealthTextSecondary)
                        }
                    }
                )
            }

            // Settings & Security Config Dialog
            if (showSettingsDialog) {
                AlertDialog(
                    onDismissRequest = { showSettingsDialog = false },
                    containerColor = StealthSurface,
                    title = {
                        Text(
                            "SECURITY & TOR ROUTING",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeonEmerald,
                            fontSize = 16.sp
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = relayServerUrl,
                                onValueChange = {
                                    relayServerUrl = it.trim()
                                    if (relayServerUrl.contains(".onion", ignoreCase = true)) {
                                        useTorSocks = true
                                    }
                                },
                                label = { Text("Relay (.onion or IP:Port)") },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = useTorSocks,
                                    onCheckedChange = { useTorSocks = it },
                                    colors = CheckboxDefaults.colors(checkedColor = NeonEmerald)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Route via Tor SOCKS5 (Orbot, port auto-detected, default 9050)",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = StealthTextPrimary
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = keepAlive,
                                    onCheckedChange = { updateKeepAlive(it) },
                                    colors = CheckboxDefaults.colors(checkedColor = NeonEmerald)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Keep connection alive in background (shows a notification)",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = StealthTextPrimary
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = ephemeralMode,
                                    onCheckedChange = { updateEphemeralMode(it) },
                                    colors = CheckboxDefaults.colors(checkedColor = NeonEmerald)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Ephemeral mode (RAM only): the identity is not saved and a new one is created at every launch",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = StealthTextPrimary
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = lockEnabled,
                                    onCheckedChange = { updateLockEnabled(it) },
                                    colors = CheckboxDefaults.colors(checkedColor = NeonEmerald)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Require the device screen lock (PIN, pattern, password) to open the app",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = StealthTextPrimary
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showSettingsDialog = false
                                var cleanUrl = relayServerUrl.trim().trimEnd('/')
                                if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                                    cleanUrl = "http://$cleanUrl"
                                }
                                relayServerUrl = cleanUrl
                                val p = getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE)
                                val editor = p.edit()
                                if (!ephemeralMode) {
                                    editor.putString("relay_url", relayServerUrl)
                                } else {
                                    editor.remove("relay_url")
                                }
                                editor.putBoolean("use_tor", useTorSocks).apply()
                                applyNetworkConfig()
                                myPgpKeys?.let { connectToRelayMailbox(it) }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                        ) {
                            Text("SAVE CONFIG", color = Color(0xFF002914), fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSettingsDialog = false }) {
                            Text("CANCEL", color = StealthTextSecondary)
                        }
                    }
                )
            }

            // Safety Code MITM Verification Dialog
            if (showSafetyCodeDialog) {
                val safetyNumber = if (myPgpKeys != null && recipientFingerprint.isNotBlank()) {
                    StandalonePgpEngine.generateSafetyCode(myPgpKeys!!.fingerprint, recipientFingerprint)
                } else {
                    "PLEASE PASTE PEER KEY FIRST"
                }
                AlertDialog(
                    onDismissRequest = { showSafetyCodeDialog = false },
                    containerColor = StealthSurface,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = NeonEmerald)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "SAFETY CODE VERIFICATION",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = NeonEmerald,
                                fontSize = 14.sp
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Compare these 30 security digits with your peer (by voice call or in person). If they match, the key you saved is very likely your peer's real key and was not swapped on the way. A mismatch means do not trust the key:",
                                color = StealthTextSecondary,
                                fontSize = 11.sp
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(StealthBackground)
                                    .border(1.dp, NeonEmerald.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = safetyNumber,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonEmerald,
                                    fontSize = 13.sp,
                                    letterSpacing = 1.sp,
                                    lineHeight = 20.sp
                                )
                            }
                            if (recipientFingerprint.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isPeerVerified = !isPeerVerified }
                                ) {
                                    Checkbox(
                                        checked = isPeerVerified,
                                        onCheckedChange = { isPeerVerified = it },
                                        colors = CheckboxDefaults.colors(checkedColor = NeonEmerald)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Mark Peer as Verified",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPeerVerified) NeonEmerald else StealthTextPrimary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { showSafetyCodeDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                        ) {
                            Text("DONE", color = Color(0xFF002914), fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun LockedScreen() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(StealthBackground),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = NeonEmerald, modifier = Modifier.size(40.dp))
                Text("LOCKED", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = NeonEmerald, fontSize = 16.sp)
                Button(
                    onClick = { requestUnlock() },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald)
                ) {
                    Text("UNLOCK", color = Color(0xFF002914), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    fun SheetAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = color)
        }
    }

    @Composable
    fun SignatureWarning(text: String) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(AlertCrimson.copy(alpha = 0.22f))
                .border(1.dp, AlertCrimson.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = AlertCrimson
            )
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

    @Composable
    fun RelayStatusBanner(status: RelayStatus) {
        val (text, color) = when (status) {
            RelayStatus.ORBOT_NOT_RUNNING ->
                "Orbot is not running. Start Orbot and connect it to Tor, then return here." to AlertCrimson
            RelayStatus.BUILDING_CIRCUIT ->
                "Connecting through Tor. This can take a minute." to StealthTextSecondary
            RelayStatus.TIMED_OUT ->
                "Connection timed out. Retrying automatically." to AlertCrimson
            RelayStatus.AUTH_FAILED ->
                "The relay rejected this mailbox's access token and will not retry. Create a new identity or check the relay." to AlertCrimson
            RelayStatus.DISCONNECTED ->
                "Not connected to the relay. Retrying automatically." to StealthTextSecondary
            RelayStatus.CONNECTED -> return
        }
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(StealthSurface)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }

    @Composable
    fun StatusBadge(label: String, isActive: Boolean) {
        val color = if (isActive) NeonEmerald else AlertCrimson
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = color
            )
        }
    }

    @Composable
    fun MessageBubble(
        message: ChatMessage,
        onToggleArmor: () -> Unit,
        onManualDecrypt: () -> Unit
    ) {
        val align = if (message.isOutgoing) Alignment.End else Alignment.Start
        val bubbleColor = if (message.isOutgoing) StealthSurfaceVariant else StealthSurface
        val borderColor = if (message.isOutgoing) NeonEmerald.copy(alpha = 0.4f) else Color.DarkGray
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = align
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bubbleColor)
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (message.isOutgoing) "OUTGOING (E2EE)" else "INCOMING",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            color = if (message.isOutgoing) NeonEmerald else WarningAmber
                        )
                        Text(
                            text = timeFormat.format(Date(message.timestamp)),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            color = StealthTextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (!message.isOutgoing && message.isDecrypted) {
                        when (message.signatureStatus) {
                            SignatureStatus.VERIFIED -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        tint = NeonEmerald,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "VERIFIED SIGNATURE" + (message.signerFingerprint?.let { " · ${it.take(8)}" } ?: ""),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp,
                                        color = NeonEmerald
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            SignatureStatus.UNSIGNED -> {
                                SignatureWarning("UNSIGNED MESSAGE")
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            SignatureStatus.INVALID_SIGNATURE -> {
                                SignatureWarning("INVALID SIGNATURE — UNVERIFIED SENDER")
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            null -> {}
                        }
                    }

                    if (message.isDecrypted && message.plaintext != null) {
                        val text = message.plaintext!!

                        var isMedia by remember(message.id) { mutableStateOf(false) }
                        var mimeType by remember(message.id) { mutableStateOf("") }
                        var filename by remember(message.id) { mutableStateOf("") }
                        var bitmapImage by remember(message.id) { mutableStateOf<ImageBitmap?>(null) }
                        var displayText by remember(message.id) { mutableStateOf<String?>(null) }

                        LaunchedEffect(text) {
                            // Parsing, Base64 and bitmap decoding can be heavy: keep them off the main thread.
                            val decoded = withContext(Dispatchers.Default) { decodeBubble(text) }
                            isMedia = decoded.isMedia
                            mimeType = decoded.mimeType
                            filename = decoded.filename
                            bitmapImage = decoded.bitmap
                            displayText = decoded.text
                        }

                        if (isMedia) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (bitmapImage != null) {
                                    Image(
                                        bitmap = bitmapImage!!,
                                        contentDescription = filename,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 240.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(StealthBackground)
                                            .padding(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    ) {
                                        Icon(Icons.Default.AttachFile, contentDescription = null, tint = NeonEmerald)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(filename, color = StealthTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            Text(mimeType, color = StealthTextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                        }
                                    }
                                }
                                Text("📎 $filename", fontSize = 10.sp, color = NeonEmerald, fontFamily = FontFamily.Monospace)
                            }
                        } else {
                            Text(
                                text = displayText ?: "",
                                color = StealthTextPrimary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Encrypted Payload (In-RAM)",
                                color = WarningAmber,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                        TextButton(
                            onClick = onManualDecrypt,
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("RETRY DECRYPT", color = NeonEmerald, fontSize = 10.sp)
                        }
                    }

                    if (message.showRawArmor) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(4.dp)
                        ) {
                            Text(
                                text = message.ciphertext,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                color = StealthTextSecondary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = if (message.showRawArmor) "HIDE ARMOR" else "VIEW ARMOR",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            color = StealthTextSecondary,
                            modifier = Modifier.clickable { onToggleArmor() }
                        )
                    }
                }
            }
        }
    }
}

/** What a message bubble shows, computed off the main thread. */
class DecodedBubble(
    val isMedia: Boolean,
    val mimeType: String,
    val filename: String,
    val bitmap: ImageBitmap?,
    val text: String?
)

private const val MAX_BUBBLE_IMAGE_EDGE = 1024

/** Largest power-of-two sample size that keeps both edges at or below [maxEdge]. */
internal fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while ((width / sample) > maxEdge || (height / sample) > maxEdge) sample *= 2
    return sample
}

private fun decodeBubble(text: String): DecodedBubble {
    val wire = WireFormat.parse(text.toByteArray(Charsets.UTF_8))
        ?: return DecodedBubble(false, "", "", null, text)       // not a v2 envelope: show as-is
    if (!wire.isMedia) return DecodedBubble(false, "", "", null, wire.text ?: "")

    val mime = wire.mimeType ?: ""
    val name = wire.filename ?: ""
    var bitmap: ImageBitmap? = null
    if (mime.startsWith("image/")) {
        try {
            val bytes = WireFormat.decodeMediaData(wire)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_BUBBLE_IMAGE_EDGE)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { bitmap = it.asImageBitmap() }
        } catch (e: Exception) {
            // keep the file card
        } catch (e: OutOfMemoryError) {
            // keep the file card
        }
    }
    return DecodedBubble(true, mime, name, bitmap, null)
}
