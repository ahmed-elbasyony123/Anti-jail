package com.ghostkey.app.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import com.ghostkey.app.audio.PlaybackState
import com.ghostkey.app.data.ChatMessage
import com.ghostkey.app.data.Contact
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.ui.*
import com.ghostkey.app.ui.components.MessageBubble
import com.ghostkey.app.ui.dialogs.IntroduceContactDialog
import java.util.Locale

/**
 * Modern Telegram-style Clean Chat Screen with Anthropic design tokens.
 *
 * Replaces the legacy cluttered single-screen UI:
 * - Zero KeyBar clutter at the top.
 * - Dynamic profile header with avatar and Tor connectivity status.
 * - Floating Pill input bar with dynamic mic-to-send morphing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanChatScreen(
    chatTitle: String,
    chatSubtitle: String,
    isGroup: Boolean = false,
    messages: List<ChatMessage>,
    playbackState: PlaybackState,
    relayStatus: RelayStatus,
    isRecordingAudio: Boolean,
    recordElapsedMs: Long,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendMedia: (Uri) -> Unit,
    onMicClick: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onAudioPlayPause: (ChatMessage) -> Unit,
    onAudioSeek: (ChatMessage, Float) -> Unit,
    onManualDecrypt: (ChatMessage) -> Unit = {},
    onOpenSafetyCode: () -> Unit,
    onOpenSettings: () -> Unit = {},
    disappearingTtlSeconds: Long? = null,
    onSetDisappearingTimer: (Long?) -> Unit = {},
    availableContacts: List<Contact> = emptyList(),
    onIntroduceContact: (Contact) -> Unit = {},
    onAcceptIntroduction: (alias: String, dropId: String, armoredKey: String, safetyCode: String?) -> Unit = { _, _, _, _ -> }
) {
    var inputText by remember { mutableStateOf("") }
    var showTtlMenu by remember { mutableStateOf(false) }
    var showIntroduceDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) onSendMedia(uri)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    BackHandler {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (isGroup) AnthropicSlate else AnthropicTerracotta),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isGroup) {
                                Icon(Icons.Default.Groups, contentDescription = null, tint = AnthropicCream, modifier = Modifier.size(20.dp))
                            } else {
                                Text(
                                    text = chatTitle.take(1).uppercase(),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AnthropicCream
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = chatTitle,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = AnthropicCream,
                                maxLines = 1
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when (relayStatus) {
                                    RelayStatus.CONNECTED -> AnthropicSage
                                    RelayStatus.BUILDING_CIRCUIT -> AnthropicTerracotta
                                    RelayStatus.AUTH_FAILED, RelayStatus.ORBOT_NOT_RUNNING -> Color(0xFFD32F2F)
                                    else -> AnthropicTextMuted
                                }
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(dotColor)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = chatSubtitle,
                                    fontSize = 10.5.sp,
                                    color = AnthropicTextMuted,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AnthropicCream)
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showTtlMenu = true }) {
                            Icon(
                                Icons.Default.HourglassBottom,
                                contentDescription = "Disappearing Messages",
                                tint = if (disappearingTtlSeconds != null) AnthropicTerracotta else AnthropicTextMuted
                            )
                        }
                        DropdownMenu(
                            expanded = showTtlMenu,
                            onDismissRequest = { showTtlMenu = false },
                            modifier = Modifier.background(AnthropicSurface)
                        ) {
                            Text(
                                "Disappearing Messages Timer",
                                color = AnthropicCream,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                            val options = listOf(
                                null to "Off (Never delete)",
                                30L to "30 Seconds",
                                300L to "5 Minutes",
                                3600L to "1 Hour",
                                86400L to "24 Hours",
                                604800L to "7 Days"
                            )
                            for ((sec, label) in options) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = label,
                                            color = if (disappearingTtlSeconds == sec) AnthropicTerracotta else AnthropicCream,
                                            fontWeight = if (disappearingTtlSeconds == sec) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        )
                                    },
                                    onClick = {
                                        onSetDisappearingTimer(sec)
                                        showTtlMenu = false
                                    }
                                )
                            }
                        }
                    }
                    if (!isGroup) {
                        IconButton(onClick = { showIntroduceDialog = true }) {
                            Icon(Icons.Default.Handshake, contentDescription = "Introduce Contact", tint = AnthropicTerracotta)
                        }
                    }
                    IconButton(onClick = onOpenSafetyCode) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = "Safety Code", tint = AnthropicSage)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AnthropicSurface,
                    titleContentColor = AnthropicCream
                )
            )
        },
        containerColor = AnthropicDarkBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Messages List (Full Screen)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = AnthropicTextMuted,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "End-to-End Encrypted Session",
                                color = AnthropicCream,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Messages exist only in volatile RAM memory. Routed anonymously over Tor.",
                                color = AnthropicTextMuted,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            MessageBubble(
                                message = msg,
                                playbackState = playbackState,
                                onToggleArmor = {},
                                onManualDecrypt = { onManualDecrypt(msg) },
                                onAudioPlayPause = onAudioPlayPause,
                                onAudioSeek = onAudioSeek,
                                onAcceptIntroduction = onAcceptIntroduction
                            )
                        }
                    }
                }
            }

            // -----------------------------------------------------------------
            // Floating Pill Input Bar (Telegram Ergonomics)
            // -----------------------------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AnthropicSurface)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (isRecordingAudio) {
                    // Recording Voice Note State
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(AnthropicSurfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(AnthropicCrimson)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Recording: " + formatDuration(recordElapsedMs),
                                color = AnthropicCream,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onCancelRecording) {
                                Text("Cancel", color = AnthropicCrimson, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = onStopRecording,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(AnthropicTerracotta)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send audio note",
                                    tint = AnthropicCream,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                } else {
                    // Normal Input Pill State
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Attachment Clip
                        IconButton(
                            onClick = {
                                com.ghostkey.app.MainActivity.isExternalPickerActive = true
                                filePicker.launch("*/*")
                            },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Attach media",
                                tint = AnthropicTextMid
                            )
                        }

                        // Capsule Pill Input
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Encrypted message...", color = AnthropicTextMuted, fontSize = 14.sp) },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(autoCorrect = false),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = AnthropicSurfaceVariant,
                                unfocusedContainerColor = AnthropicSurfaceVariant,
                                focusedBorderColor = AnthropicTerracotta,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = AnthropicCream,
                                unfocusedTextColor = AnthropicCream
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Morphing Mic -> Send Button
                        if (inputText.isBlank()) {
                            IconButton(
                                onClick = onMicClick,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(AnthropicTerracotta)
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "Hold / Tap to record Opus voice note",
                                    tint = AnthropicCream,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    val textToSend = inputText
                                    inputText = ""
                                    onSendMessage(textToSend)
                                },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(AnthropicTerracotta)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send Encrypted Message",
                                    tint = AnthropicCream,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showIntroduceDialog) {
        IntroduceContactDialog(
            currentContactAlias = chatTitle,
            availableContacts = availableContacts,
            onDismiss = { showIntroduceDialog = false },
            onIntroduce = { contactToIntroduce ->
                showIntroduceDialog = false
                onIntroduceContact(contactToIntroduce)
            }
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", min, sec)
}
