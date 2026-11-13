package com.ghostkey.app

import android.content.Intent
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ghostkey.app.crypto.OpenKeychainHelper
import com.ghostkey.app.net.EncryptedMessageRecord
import com.ghostkey.app.net.TorRelayClient
import com.ghostkey.app.ui.*
import kotlinx.coroutines.launch
import org.json.JSONObject
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

    private lateinit var openKeychain: OpenKeychainHelper
    private val torClient = TorRelayClient()

    // Volatile in-memory state - Never saved to SQLite or disk
    private val messagesState = mutableStateListOf<ChatMessage>()
    private val isOpenKeychainConnected = mutableStateOf(false)
    private val isRelayConnected = mutableStateOf(false)

    // Configuration state
    private var recipientUserId by mutableStateOf("recipient@ghostkey.onion")
    private var recipientKeyIdHex by mutableStateOf("")
    private var myMailboxKeyId by mutableStateOf("my-ghostkey-address")
    private var relayServerUrl by mutableStateOf("http://10.0.2.2:8765")
    private var useTorSocks by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // HARDENING 1: Block screenshots, screen recording, and app thumbnail preview in recents
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // Initialize OpenKeychain binding
        openKeychain = OpenKeychainHelper(this) { isBound ->
            isOpenKeychainConnected.value = isBound
        }

        // Initialize network client
        torClient.updateConfig(relayServerUrl, useTorSocks)
        connectToRelayMailbox()

        setContent {
            GhostKeyTheme {
                MainStealthScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        torClient.disconnectWebSocket()
        openKeychain.unbindService()
    }

    private fun connectToRelayMailbox() {
        val myMailboxHash = OpenKeychainHelper.deriveMailboxHash(myMailboxKeyId)
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
                    // Automatically attempt to decrypt with OpenKeychain
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

        val recipientHash = OpenKeychainHelper.deriveMailboxHash(
            if (recipientKeyIdHex.isNotBlank()) recipientKeyIdHex else recipientUserId
        )

        val recipientUserIds = if (recipientUserId.isNotBlank()) arrayOf(recipientUserId) else null
        val recipientKeyIds = if (recipientKeyIdHex.isNotBlank()) {
            try {
                longArrayOf(recipientKeyIdHex.toLong(16))
            } catch (e: Exception) {
                null
            }
        } else null

        // Step 1: Encrypt payload using OpenKeychain
        openKeychain.encrypt(
            plainText = text,
            recipientUserIds = recipientUserIds,
            recipientKeyIds = recipientKeyIds,
            activity = this,
            onSuccess = { armoredCiphertext ->
                // Step 2: Push armored ciphertext to the blind Tor relay
                lifecycleScope.launch {
                    val result = torClient.sendMessage(recipientHash, armoredCiphertext)
                    result.onSuccess { msgId ->
                        runOnUiThread {
                            messagesState.add(
                                ChatMessage(
                                    id = msgId,
                                    isOutgoing = true,
                                    plaintext = text,
                                    ciphertext = armoredCiphertext,
                                    timestamp = System.currentTimeMillis(),
                                    isDecrypted = true
                                )
                            )
                        }
                    }.onFailure { err ->
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Send failed: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onError = { errorMsg ->
                Toast.makeText(this, "Encryption error: $errorMsg", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun decryptMessage(msg: ChatMessage) {
        openKeychain.decrypt(
            cipherText = msg.ciphertext,
            activity = this,
            onSuccess = { plainText ->
                runOnUiThread {
                    val index = messagesState.indexOfFirst { it.id == msg.id }
                    if (index != -1) {
                        messagesState[index] = messagesState[index].copy(
                            plaintext = plainText,
                            isDecrypted = true
                        )
                    }
                }
            },
            onError = { err ->
                runOnUiThread {
                    Toast.makeText(this, "Decryption error: $err", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /**
     * EMERGENCY PANIC WIPE
     * Erases all memory objects, clears lists, closes sockets, and terminates immediately.
     */
    private fun triggerEmergencyPanicWipe() {
        messagesState.clear()
        torClient.disconnectWebSocket()
        openKeychain.unbindService()
        finishAffinity()
        exitProcess(0)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainStealthScreen() {
        var inputText by remember { mutableStateOf("") }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var showSafetyCodeDialog by remember { mutableStateOf(false) }
        var isPeerVerified by remember { mutableStateOf(false) }

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
                                label = if (isOpenKeychainConnected.value) "PGP: READY" else "PGP: OFF",
                                isActive = isOpenKeychainConnected.value
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
                // Security Notice Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StealthSurfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "🔒 Zero-Knowledge RAM Store • OpenKeychain E2EE • Tor Route Ready",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = StealthTextSecondary
                    )
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
                                        "Messages are zero-disk and purged upon receipt.",
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
                                "Type secret message...",
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
                                value = recipientUserId,
                                onValueChange = { recipientUserId = it },
                                label = { Text("Recipient PGP User ID / Email") },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = recipientKeyIdHex,
                                onValueChange = { recipientKeyIdHex = it },
                                label = { Text("Recipient Key ID (Hex, optional)") },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = myMailboxKeyId,
                                onValueChange = { myMailboxKeyId = it },
                                label = { Text("My Mailbox Identifier / Fingerprint") },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = relayServerUrl,
                                onValueChange = { relayServerUrl = it },
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
                                torClient.updateConfig(relayServerUrl, useTorSocks)
                                connectToRelayMailbox()
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
                val safetyNumber = OpenKeychainHelper.computeSafetyCode(
                    myMailboxKeyId,
                    recipientKeyIdHex.ifBlank { recipientUserId }
                )
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
        val bgColor = if (message.isOutgoing) StealthSurfaceVariant else StealthSurface
        val borderColor = if (message.isOutgoing) NeonEmerald.copy(alpha = 0.3f) else StealthBorder

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = align
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
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
                            text = if (message.isOutgoing) "OUTGOING [PGP]" else "INCOMING [PGP]",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = if (message.isOutgoing) NeonEmerald else NeonCyan
                        )
                        Text(
                            text = if (message.isDecrypted) "🔓 DECRYPTED" else "🔒 ENCRYPTED",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = if (message.isDecrypted) NeonEmerald else WarningAmber
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (message.isDecrypted && message.plaintext != null) {
                        val text = message.plaintext!!
                        if (text.startsWith("{\"type\":\"media\"")) {
                            try {
                                val json = JSONObject(text)
                                val mime = json.optString("mimeType", "")
                                val filename = json.optString("filename", "file")
                                val data = json.optString("data", "")

                                if (mime.startsWith("image/") && data.contains(",")) {
                                    val base64Part = data.substringAfter(",")
                                    val imageBytes = Base64.decode(base64Part, Base64.DEFAULT)
                                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = filename,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 240.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(StealthBackground)
                                            .padding(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    ) {
                                        Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = NeonEmerald)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = filename,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = StealthTextPrimary
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Text(text = text, color = StealthTextPrimary, fontSize = 14.sp)
                            }
                        } else {
                            Text(text = text, color = StealthTextPrimary, fontSize = 14.sp)
                        }
                    } else {
                        Text(
                            text = "[PGP Encrypted Payload]",
                            color = StealthTextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onManualDecrypt,
                            colors = ButtonDefaults.buttonColors(containerColor = StealthSurfaceVariant),
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("DECRYPT VIA OPENKEYCHAIN", fontSize = 10.sp, color = NeonEmerald)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // PGP Armor expander
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onToggleArmor() }
                    ) {
                        Icon(
                            if (message.showRawArmor) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = StealthTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            if (message.showRawArmor) "Hide PGP Ciphertext" else "View PGP Ciphertext",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = StealthTextSecondary
                        )
                    }

                    if (message.showRawArmor) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = message.ciphertext,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            color = StealthTextSecondary,
                            lineHeight = 10.sp
                        )
                    }
                }
            }
        }
    }
}
