package com.ghostkey.app.ui.screens.chats

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.MainActivity
import com.ghostkey.app.audio.PlaybackState
import com.ghostkey.app.data.ChatMessage
import com.ghostkey.app.data.Contact
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.components.AvatarView
import com.ghostkey.app.ui.components.MessageBubble
import com.ghostkey.app.ui.dialogs.IntroduceContactDialog
import com.ghostkey.app.ui.theme.GhostTheme
import com.ghostkey.app.util.GhostKeyUtils
import kotlinx.coroutines.delay

/**
 * Minimal, quiet 1-to-1 / Group Chat view:
 * - Clean header with avatar, title, and overflow menu (no connection strip inside chat)
 * - Black outgoing & light-gray incoming bubbles with reply preview and waveform audio notes
 * - Merged Mic / Send button in the bottom input pill
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatViewScreen(
    chatId: String,
    chatTitle: String,
    chatSubtitle: String,
    avatarBase64: String? = null,
    isGroup: Boolean = false,
    keyChangedWarning: Boolean = false,
    messages: List<ChatMessage>,
    playbackState: PlaybackState,
    isRecordingAudio: Boolean,
    recordElapsedMs: Long,
    disappearingTtlSeconds: Long? = null,
    availableContacts: List<Contact> = emptyList(),
    onBack: () -> Unit,
    onSendMessage: (text: String, replyToId: String?) -> Unit,
    onSendMedia: (Uri) -> Unit,
    onMicClick: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onAudioPlayPause: (ChatMessage) -> Unit,
    onAudioSeek: (ChatMessage, Float) -> Unit,
    onManualDecrypt: (ChatMessage) -> Unit,
    onOpenSafetyCode: () -> Unit,
    onDismissKeyWarning: () -> Unit = {},
    onSetDisappearingTimer: (Long?) -> Unit,
    onIntroduceContact: (Contact) -> Unit,
    onAcceptIntroduction: (alias: String, dropId: String, armoredKey: String, safetyCode: String?) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteOrLeaveChat: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    var inputText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }
    var showOverflow by remember { mutableStateOf(false) }
    var showTtlSheet by remember { mutableStateOf(false) }
    var showIntroduceDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val messagesById = remember(messages) { messages.associateBy { it.id } }

    val hasExpiring = remember(messages) { messages.any { it.expiresAt != null } }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasExpiring) {
        if (hasExpiring) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) onSendMedia(uri)
    }

    BackHandler { onBack() }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.backBtn, tint = palette.textPrimary)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarView(
                            title = chatTitle,
                            seed = chatId,
                            avatarBase64 = avatarBase64,
                            isGroup = isGroup,
                            size = 38.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = chatTitle,
                                color = palette.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = chatSubtitle,
                                color = palette.textSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = palette.textPrimary)
                        }
                        DropdownMenu(
                            expanded = showOverflow,
                            onDismissRequest = { showOverflow = false },
                            modifier = Modifier.background(palette.surface)
                        ) {
                            if (!isGroup) {
                                DropdownMenuItem(
                                    text = { Text(strings.safetyCodeAction, color = palette.textPrimary) },
                                    leadingIcon = { Icon(AppIcons.VerifiedUser, contentDescription = null, tint = palette.connected) },
                                    onClick = { showOverflow = false; onOpenSafetyCode() }
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.disappearingTimerAction, color = palette.textPrimary) },
                                    leadingIcon = { Icon(AppIcons.Hourglass, contentDescription = null, tint = palette.accent) },
                                    onClick = { showOverflow = false; showTtlSheet = true }
                                )
                                DropdownMenuItem(
                                    text = { Text(strings.introduceContactAction, color = palette.textPrimary) },
                                    leadingIcon = { Icon(AppIcons.Handshake, contentDescription = null, tint = palette.accent) },
                                    onClick = { showOverflow = false; showIntroduceDialog = true }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(strings.clearHistory, color = palette.textPrimary) },
                                onClick = { showOverflow = false; onClearHistory() }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.deleteChat, color = palette.error) },
                                onClick = { showOverflow = false; onDeleteOrLeaveChat() }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            if (keyChangedWarning && !isGroup) {
                Surface(
                    color = palette.error.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = palette.error, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.keyChangedWarningText,
                            color = palette.textPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onOpenSafetyCode) {
                            Text(strings.verifyNowBtn, color = palette.error, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        TextButton(onClick = onDismissKeyWarning) {
                            Text(strings.dismissBtn, color = palette.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = palette.textTertiary,
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = strings.emptyChatMessagesTitle,
                            color = palette.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = strings.emptyChatMessagesSubtitle,
                            color = palette.textSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            MessageBubble(
                                message = msg,
                                replyTargetMessage = msg.replyToId?.let { messagesById[it] },
                                playbackState = playbackState,
                                nowMs = nowMs,
                                onManualDecrypt = { onManualDecrypt(msg) },
                                onAudioPlayPause = onAudioPlayPause,
                                onAudioSeek = onAudioSeek,
                                onReplyToMessage = { replyingTo = it },
                                onAcceptIntroduction = onAcceptIntroduction
                            )
                        }
                    }
                }
            }

            if (replyingTo != null) {
                val target = replyingTo!!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.accent)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${strings.replyPrefix} ${if (target.isOutgoing) "You" else (target.senderAlias ?: chatTitle)}",
                            color = palette.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = target.previewText(""),
                            color = palette.textSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { replyingTo = null }) {
                        Icon(Icons.Default.Close, contentDescription = strings.cancelBtn, tint = palette.textSecondary)
                    }
                }
            }

            ChatComposerBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                isRecordingAudio = isRecordingAudio,
                recordElapsedMs = recordElapsedMs,
                onAttachClick = {
                    MainActivity.isExternalPickerActive = true
                    filePicker.launch("*/*")
                },
                onMicClick = onMicClick,
                onSendClick = {
                    val toSend = inputText
                    val replyId = replyingTo?.id
                    inputText = ""
                    replyingTo = null
                    onSendMessage(toSend, replyId)
                },
                onStopRecording = onStopRecording,
                onCancelRecording = onCancelRecording
            )
        }
    }

    if (showTtlSheet) {
        DisappearingTimerDialog(
            currentTtl = disappearingTtlSeconds,
            onSelect = { sec ->
                onSetDisappearingTimer(sec)
                showTtlSheet = false
            },
            onDismiss = { showTtlSheet = false }
        )
    }

    if (showIntroduceDialog) {
        IntroduceContactDialog(
            currentContactAlias = chatTitle,
            availableContacts = availableContacts,
            onDismiss = { showIntroduceDialog = false },
            onIntroduce = { contact ->
                showIntroduceDialog = false
                onIntroduceContact(contact)
            }
        )
    }
}

@Composable
private fun ChatComposerBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    isRecordingAudio: Boolean,
    recordElapsedMs: Long,
    onAttachClick: () -> Unit,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (isRecordingAudio) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(palette.surface)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(palette.error)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${strings.recordingPrefix}: ${GhostKeyUtils.formatDurationMmSs(recordElapsedMs)}",
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancelRecording) {
                        Text(strings.cancelBtn, color = palette.error, fontSize = 13.sp)
                    }
                    IconButton(
                        onClick = onStopRecording,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(palette.accent)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send audio",
                            tint = palette.accentText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onAttachClick, modifier = Modifier.size(42.dp)) {
                    Icon(AppIcons.AttachFile, contentDescription = "Attach", tint = palette.textSecondary)
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    placeholder = { Text(strings.messageInputPlaceholder, color = palette.textTertiary, fontSize = 14.sp) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.surface,
                        unfocusedContainerColor = palette.surface,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                val isTextEmpty = inputText.isBlank()
                IconButton(
                    onClick = if (isTextEmpty) onMicClick else onSendClick,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isTextEmpty) palette.surfaceVariant else palette.accent)
                ) {
                    Icon(
                        imageVector = if (isTextEmpty) AppIcons.Mic else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isTextEmpty) "Record" else "Send",
                        tint = if (isTextEmpty) palette.textPrimary else palette.accentText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DisappearingTimerDialog(
    currentTtl: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val options = listOf(
        null to strings.timerOff,
        30L to "30s",
        300L to "5m",
        3600L to "1h",
        86400L to "24h",
        604800L to "7d"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text(strings.timerTitle, color = palette.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                options.forEach { (sec, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(sec) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentTtl == sec,
                            onClick = { onSelect(sec) },
                            colors = RadioButtonDefaults.colors(selectedColor = palette.accent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, color = palette.textPrimary, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancelBtn, color = palette.textSecondary)
            }
        }
    )
}
