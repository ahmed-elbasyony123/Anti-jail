package com.ghostkey.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.data.ChatSummary
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.ui.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Modern Telegram-style Chats & Contacts List Screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListScreen(
    chats: List<ChatSummary>,
    relayStatus: RelayStatus,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectChat: (ChatSummary) -> Unit,
    onNewDirectChat: () -> Unit,
    onNewGroupChat: () -> Unit,
    onShowMyQr: () -> Unit = {},
    onPairContactQr: () -> Unit = {},
    onOpenSecurityDiagnostics: () -> Unit = {}
) {
    var showFabMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { onOpenSecurityDiagnostics() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "GhostKey",
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.sp,
                                color = AnthropicCream
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val dotColor = when (relayStatus) {
                                RelayStatus.CONNECTED -> AnthropicSage
                                RelayStatus.BUILDING_CIRCUIT -> AnthropicTerracotta
                                RelayStatus.AUTH_FAILED, RelayStatus.ORBOT_NOT_RUNNING -> Color(0xFFD32F2F)
                                else -> AnthropicTextMuted
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                        }
                        val statusText = when (relayStatus) {
                            RelayStatus.CONNECTED -> "Connected over Tor 🧅"
                            RelayStatus.BUILDING_CIRCUIT -> "Establishing Tor circuit..."
                            RelayStatus.ORBOT_NOT_RUNNING -> "Orbot / Tor not running"
                            RelayStatus.TIMED_OUT -> "Connection timed out"
                            RelayStatus.AUTH_FAILED -> "Mailbox auth failed"
                            RelayStatus.DISCONNECTED -> "Disconnected"
                        }
                        Text(
                            text = statusText,
                            fontSize = 11.sp,
                            color = AnthropicTextMuted
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = AnthropicCream)
                    }
                },
                actions = {
                    IconButton(onClick = onShowMyQr) {
                        Icon(Icons.Default.QrCode, contentDescription = "My QR Code", tint = AnthropicTerracotta)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = AnthropicCream)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AnthropicSurface,
                    titleContentColor = AnthropicCream
                )
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { showFabMenu = !showFabMenu },
                    containerColor = AnthropicTerracotta,
                    contentColor = AnthropicCream,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = if (showFabMenu) Icons.Default.Close else Icons.Default.Edit,
                        contentDescription = "New Conversation"
                    )
                }

                DropdownMenu(
                    expanded = showFabMenu,
                    onDismissRequest = { showFabMenu = false },
                    modifier = Modifier.background(AnthropicSurface)
                ) {
                    DropdownMenuItem(
                        text = { Text("Pair via QR / SAS (Briar-Style)", color = AnthropicCream) },
                        leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = AnthropicSage) },
                        onClick = {
                            showFabMenu = false
                            onPairContactQr()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("New Direct E2EE Chat", color = AnthropicCream) },
                        leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null, tint = AnthropicTerracotta) },
                        onClick = {
                            showFabMenu = false
                            onNewDirectChat()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("New Encrypted Group", color = AnthropicCream) },
                        leadingIcon = { Icon(Icons.Default.GroupAdd, contentDescription = null, tint = AnthropicSlate) },
                        onClick = {
                            showFabMenu = false
                            onNewGroupChat()
                        }
                    )
                }
            }
        },
        containerColor = AnthropicDarkBg
    ) { innerPadding ->
        if (chats.isEmpty()) {
            // Telegram-style Empty State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(AnthropicTerracotta.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = AnthropicTerracotta,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "No Active Chats in RAM",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AnthropicCream
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Start a 1-to-1 encrypted conversation or create an untraceable group chat over Tor.",
                        fontSize = 13.sp,
                        color = AnthropicTextMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onNewDirectChat,
                        colors = ButtonDefaults.buttonColors(containerColor = AnthropicTerracotta),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Peer Public Key", fontWeight = FontWeight.Bold, color = AnthropicCream)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(chats, key = { it.chatId }) { chat ->
                    ChatItemRow(
                        chat = chat,
                        onClick = { onSelectChat(chat) }
                    )
                    HorizontalDivider(
                        color = AnthropicBorderSubtle,
                        modifier = Modifier.padding(start = 76.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatItemRow(
    chat: ChatSummary,
    onClick: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with Initial
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(if (chat.isGroup) AnthropicSlate else AnthropicTerracotta),
            contentAlignment = Alignment.Center
        ) {
            if (chat.isGroup) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = AnthropicCream, modifier = Modifier.size(26.dp))
            } else {
                Text(
                    text = chat.avatarLetter.take(1).uppercase(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = AnthropicCream
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title and Last Message
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = chat.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = AnthropicCream,
                        maxLines = 1
                    )
                    if (!chat.isGroup) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (chat.isVerified) Icons.Default.Shield else Icons.Default.Security,
                            contentDescription = if (chat.isVerified) "Verified SAS Peer" else "Unverified Peer",
                            tint = if (chat.isVerified) AnthropicSage else AnthropicTextMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                if (chat.lastMessageTime > 0L) {
                    Text(
                        text = timeFormat.format(Date(chat.lastMessageTime)),
                        fontSize = 11.sp,
                        color = AnthropicTextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chat.lastMessageText.ifBlank { if (chat.isGroup) "Encrypted Group Session" else "Direct E2EE Session" },
                    fontSize = 13.sp,
                    color = AnthropicTextMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (chat.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AnthropicSage)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = chat.unreadCount.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = AnthropicCream
                        )
                    }
                }
            }
        }
    }
}
