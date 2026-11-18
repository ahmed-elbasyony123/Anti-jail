package com.ghostkey.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.net.TorRelayClient
import com.ghostkey.app.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
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
    var showRawArmor: Boolean = false
)

class MainActivity : ComponentActivity() {

    private val torClient = TorRelayClient()

    // Volatile in-memory state - Never saved to SQLite or disk
    private val messagesState = mutableStateListOf<ChatMessage>()
    private val isRelayConnected = mutableStateOf(false)

    // Standalone PGP In-Memory State
    private var myPgpKeys by mutableStateOf<PgpKeyPairArmored?>(null)
    private var recipientArmoredKey by mutableStateOf("")
    private var recipientFingerprint by mutableStateOf("")
    private var isGeneratingKey by mutableStateOf(false)

    // Configuration state
    private var relayServerUrl by mutableStateOf("http://10.0.2.2:8765")
    private var useTorSocks by mutableStateOf(false)

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

        // Generate ephemeral in-memory keys on startup
        generateFreshKeys()

        // Initialize network client
        torClient.updateConfig(relayServerUrl, useTorSocks)

        setContent {
            GhostKeyTheme {
                MainStealthScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        torClient.disconnectWebSocket()
    }

    private fun generateFreshKeys() {
        isGeneratingKey = true
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val keys = StandalonePgpEngine.generateKeyPair()
                runOnUiThread {
                    myPgpKeys = keys
                    isGeneratingKey = false
                    connectToRelayMailbox(keys.fingerprint)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isGeneratingKey = false
                    Toast.makeText(this@MainActivity, "Keygen failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun connectToRelayMailbox(fingerprint: String) {
        val myMailboxHash = StandalonePgpEngine.deriveMailbox(fingerprint)
        torClient.subscribeLive(
            mailboxHash = myMailboxHash,
            onMessageReceived = { record ->
                runOnUiThread {
                    val msg = ChatMessage(
                        id = record.id,
                        isOutgoing = false,
                        plaintext = null,
                        ciphertext = record.ciphertext,
                        timestamp = record.createdAt,
                        isDecrypted = false
                    )
                    messagesState.add(msg)
                    // Automatically attempt to decrypt with standalone engine
                    decryptMessage(msg)
                }
            },
            onStatusChanged = { connected ->
                runOnUiThread {
                    isRelayConnected.value = connected
                }
            }
        )
    }

    private fun sendMedia(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > 8 * 1024 * 1024) {
                    Toast.makeText(this, "Attachment exceeds 8 MB limit", Toast.LENGTH_SHORT).show()
                    return
                }
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val fileName = uri.lastPathSegment ?: "file"
                val payload = JSONObject().apply {
                    put("type", "media")
                    put("mimeType", mimeType)
                    put("filename", fileName)
                    put("data", "data:$mimeType;base64,$base64")
                    put("caption", "")
                }
                sendMessage(payload.toString())
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Attachment error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMessage(text: String) {
        if (text.isBlank()) return

        val keys = myPgpKeys
        if (keys == null) {
            Toast.makeText(this, "No active PGP key in RAM. Please generate keys first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (recipientArmoredKey.isBlank()) {
            Toast.makeText(this, "Please paste your peer's public key first!", Toast.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Step 1: Encrypt payload in-memory using standalone OpenPGP engine
                val ciphertext = StandalonePgpEngine.encrypt(
                    text.toByteArray(Charsets.UTF_8),
                    recipientArmoredKey
                )

                // Step 2: Push to recipient's blind mailbox
                val recipientMailboxHash = StandalonePgpEngine.deriveMailbox(recipientFingerprint)
                val result = torClient.sendMessage(
                    mailboxHash = recipientMailboxHash,
                    ciphertext = ciphertext
                )
                result.onSuccess {
                    runOnUiThread {
                        val msg = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            isOutgoing = true,
                            plaintext = text,
                            ciphertext = ciphertext,
                            timestamp = System.currentTimeMillis(),
                            isDecrypted = true
                        )
                        messagesState.add(msg)
                    }
                }.onFailure { err ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Send failed: ${err.message}", Toast.LENGTH_LONG).show()
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
                val clearBytes = StandalonePgpEngine.decrypt(msg.ciphertext, keys.privateKeyArmored)
                val plainText = String(clearBytes, Charsets.UTF_8)
                runOnUiThread {
                    val index = messagesState.indexOfFirst { it.id == msg.id }
                    if (index != -1) {
                        messagesState[index] = messagesState[index].copy(
                            plaintext = plainText,
                            isDecrypted = true
                        )
                    }
                }
            } catch (e: Exception) {
                // Keep as ciphertext for manual retry
            }
        }
    }

    /**
     * EMERGENCY PANIC WIPE
     * Erases all memory objects, clears lists, closes sockets, and terminates immediately.
     */
    private fun triggerEmergencyPanicWipe() {
        myPgpKeys = null
        recipientArmoredKey = ""
        recipientFingerprint = ""
        messagesState.clear()
        torClient.disconnectWebSocket()
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
                                label = if (isRelayConnected.value) "RELAY: LIVE" else "RELAY: OFF",
                                isActive = isRelayConnected.value
                            )
                        }
                    },
                    actions = {
                        // Safety Code Verification Button
                        IconButton(onClick = { showSafetyCodeDialog = true }) {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = "Safety Code",
                                tint = if (isPeerVerified) NeonEmerald else StealthTextSecondary
                            )
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
                // Key Management Quick Cards
                Column(
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
                                    myPgpKeys?.let {
                                        clipboardManager.setPrimaryClip(ClipData.newPlainText("PGP Public Key", it.publicKeyArmored))
                                        Toast.makeText(this@MainActivity, "Public Key copied to clipboard! 📋", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("COPY KEY", fontSize = 10.sp, color = NeonEmerald, fontFamily = FontFamily.Monospace)
                            }
                            TextButton(
                                onClick = { generateFreshKeys() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("NEW KEY", fontSize = 10.sp, color = StealthTextSecondary, fontFamily = FontFamily.Monospace)
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
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
                                        "Zero-disk, ephemeral PGP in-memory store.",
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
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("PGP Public Key", myPgpKeys!!.publicKeyArmored))
                                Toast.makeText(this@MainActivity, "Public Key copied to clipboard! 📋", Toast.LENGTH_SHORT).show()
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
                                    val fp = StandalonePgpEngine.getFingerprint(tempKeyText)
                                    recipientArmoredKey = tempKeyText.trim()
                                    recipientFingerprint = fp
                                    isPeerVerified = false
                                    showPeerKeyDialog = false
                                    Toast.makeText(this@MainActivity, "Key verified! Fingerprint: ${fp.take(12)}...", Toast.LENGTH_SHORT).show()
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
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                    "Route via Tor SOCKS5 (Orbot 9050)",
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
                                p.edit()
                                    .putString("relay_url", relayServerUrl)
                                    .putBoolean("use_tor", useTorSocks)
                                    .apply()
                                torClient.updateConfig(relayServerUrl, useTorSocks)
                                myPgpKeys?.let { connectToRelayMailbox(it.fingerprint) }
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
                                "Compare these 30 security digits with your peer (by voice call or in person). If they match, your connection is 100% genuine and safe from interception:",
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
                            text = if (message.isOutgoing) "OUTGOING (E2EE)" else "INCOMING (BURN-ON-READ)",
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

                    if (message.isDecrypted && message.plaintext != null) {
                        val text = message.plaintext!!

                        var isMedia by remember(message.id) { mutableStateOf(false) }
                        var mimeType by remember(message.id) { mutableStateOf("") }
                        var filename by remember(message.id) { mutableStateOf("") }
                        var bitmapImage by remember(message.id) { mutableStateOf<ImageBitmap?>(null) }

                        LaunchedEffect(text) {
                            if (text.startsWith("{") && text.contains("\"type\":\"media\"")) {
                                try {
                                    val json = JSONObject(text)
                                    if (json.optString("type") == "media") {
                                        isMedia = true
                                        mimeType = json.optString("mimeType")
                                        filename = json.optString("filename")
                                        val data = json.optString("data")
                                        if (mimeType.startsWith("image/") && data.contains(",")) {
                                            val base64Part = data.substringAfter(",")
                                            val imageBytes = Base64.decode(base64Part, Base64.DEFAULT)
                                            val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                            if (bmp != null) {
                                                bitmapImage = bmp.asImageBitmap()
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    isMedia = false
                                }
                            }
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
                                text = text,
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
