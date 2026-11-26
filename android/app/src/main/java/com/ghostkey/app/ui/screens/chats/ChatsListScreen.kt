package com.ghostkey.app.ui.screens.chats

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostkey.app.data.ChatSummary
import com.ghostkey.app.data.ContactRequest
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.ui.AppIcons
import com.ghostkey.app.ui.components.AvatarView
import com.ghostkey.app.ui.components.PrimaryActionButton
import com.ghostkey.app.ui.components.SegmentedPillToggle
import com.ghostkey.app.ui.icons.ExtraIcons
import com.ghostkey.app.ui.theme.GhostTheme
import com.ghostkey.app.ui.theme.statusColor
import com.ghostkey.app.util.GhostKeyUtils

private enum class ChatFilterTab { ALL, PRIVATE, GROUPS }

/**
 * Clean home screen:
 * - "GhostKey" bold title colored directly by connection status
 * - Inline search bar & conditional Private/Group toggle when both exist
 * - Long-press selection bar with Pin, Mute, Archive, and 3-dot overflow menu
 */
@Composable
fun ChatsListScreen(
    chats: List<ChatSummary>,
    relayStatus: RelayStatus,
    contactRequests: List<ContactRequest> = emptyList(),
    onSelectChat: (ChatSummary) -> Unit,
    onNewDirectChat: () -> Unit,
    onQuickPairClick: () -> Unit = {},
    onPairContactQr: () -> Unit,
    onNewGroupChat: () -> Unit,
    onAcceptContactRequest: (String) -> Unit = {},
    onDeclineContactRequest: (String) -> Unit = {},
    onOpenSecurityDiagnostics: () -> Unit,
    onTogglePinChats: (Set<String>) -> Unit,
    onToggleMuteChats: (Set<String>) -> Unit,
    onToggleArchiveChats: (Set<String>) -> Unit,
    onClearChatsHistory: (Set<String>) -> Unit,
    onDeleteChats: (Set<String>) -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings

    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var filterTab by remember { mutableStateOf(ChatFilterTab.ALL) }
    var viewingArchived by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var showFabMenu by remember { mutableStateOf(false) }

    val inSelectionMode = selectedIds.isNotEmpty()

    BackHandler(enabled = inSelectionMode || viewingArchived || searchActive) {
        when {
            inSelectionMode -> selectedIds = emptySet()
            searchActive -> {
                searchActive = false
                searchQuery = ""
            }
            viewingArchived -> viewingArchived = false
        }
    }

    val archivedChats = remember(chats) { chats.filter { it.isArchived } }
    val activeChats = remember(chats) { chats.filterNot { it.isArchived } }
    val hasPrivate = remember(activeChats) { activeChats.any { !it.isGroup } }
    val hasGroups = remember(activeChats) { activeChats.any { it.isGroup } }
    val showSegmentToggle = !viewingArchived && hasPrivate && hasGroups

    LaunchedEffect(showSegmentToggle) {
        if (!showSegmentToggle && filterTab != ChatFilterTab.ALL) {
            filterTab = ChatFilterTab.ALL
        }
    }

    val baseList = if (viewingArchived) archivedChats else activeChats
    val visibleChats = remember(baseList, filterTab, searchQuery, showSegmentToggle) {
        baseList.filter { chat ->
            val matchesFilter = if (!showSegmentToggle) true else when (filterTab) {
                ChatFilterTab.ALL -> true
                ChatFilterTab.PRIVATE -> !chat.isGroup
                ChatFilterTab.GROUPS -> chat.isGroup
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                chat.title.contains(searchQuery, ignoreCase = true) ||
                    chat.lastMessageText.contains(searchQuery, ignoreCase = true)
            }
            matchesFilter && matchesSearch
        }
    }

    Scaffold(
        containerColor = palette.background,
        floatingActionButton = {
            if (!inSelectionMode && !viewingArchived) {
                Box(modifier = Modifier.padding(bottom = 86.dp)) {
                    FloatingActionButton(
                        onClick = { showFabMenu = !showFabMenu },
                        containerColor = palette.accent,
                        contentColor = palette.accentText,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = if (showFabMenu) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = strings.addContactBtn
                        )
                    }
                    DropdownMenu(
                        expanded = showFabMenu,
                        onDismissRequest = { showFabMenu = false },
                        modifier = Modifier.background(palette.surface)
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.newDirectChat, color = palette.textPrimary) },
                            leadingIcon = { Icon(AppIcons.PersonAdd, contentDescription = null, tint = palette.accent) },
                            onClick = {
                                showFabMenu = false
                                onNewDirectChat()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.quickPairAction, color = palette.textPrimary) },
                            leadingIcon = { Icon(AppIcons.Handshake, contentDescription = null, tint = palette.accent) },
                            onClick = {
                                showFabMenu = false
                                onQuickPairClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.scanQrPair, color = palette.textPrimary) },
                            leadingIcon = { Icon(AppIcons.QrCodeScanner, contentDescription = null, tint = palette.connected) },
                            onClick = {
                                showFabMenu = false
                                onPairContactQr()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.newEncryptedGroup, color = palette.textPrimary) },
                            leadingIcon = { Icon(AppIcons.GroupAdd, contentDescription = null, tint = palette.textSecondary) },
                            onClick = {
                                showFabMenu = false
                                onNewGroupChat()
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            if (inSelectionMode) {
                SelectionTopBar(
                    selectedCount = selectedIds.size,
                    showMenu = showSelectionMenu,
                    onToggleMenu = { showSelectionMenu = it },
                    onClearSelection = { selectedIds = emptySet() },
                    onPin = {
                        onTogglePinChats(selectedIds)
                        selectedIds = emptySet()
                    },
                    onMute = {
                        onToggleMuteChats(selectedIds)
                        selectedIds = emptySet()
                    },
                    onArchive = {
                        onToggleArchiveChats(selectedIds)
                        selectedIds = emptySet()
                    },
                    onClearHistory = {
                        onClearChatsHistory(selectedIds)
                        selectedIds = emptySet()
                    },
                    onDelete = {
                        onDeleteChats(selectedIds)
                        selectedIds = emptySet()
                    },
                    onSelectAll = {
                        selectedIds = visibleChats.map { it.chatId }.toSet()
                    }
                )
            } else {
                HomeTopBar(
                    relayStatus = relayStatus,
                    viewingArchived = viewingArchived,
                    archivedCount = archivedChats.size,
                    searchActive = searchActive,
                    onBackFromArchive = { viewingArchived = false },
                    onTitleClick = onOpenSecurityDiagnostics,
                    onToggleSearch = {
                        searchActive = !searchActive
                        if (!searchActive) searchQuery = ""
                    }
                )
            }

            AnimatedVisibility(visible = searchActive && !inSelectionMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(strings.searchPlaceholder, color = palette.textTertiary, fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = palette.textSecondary) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = palette.textSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = palette.surface,
                        unfocusedContainerColor = palette.surface,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            if (showSegmentToggle && !inSelectionMode) {
                SegmentedPillToggle(
                    options = listOf(
                        ChatFilterTab.ALL to strings.segmentAll,
                        ChatFilterTab.PRIVATE to strings.segmentPrivate,
                        ChatFilterTab.GROUPS to strings.segmentGroup
                    ),
                    selected = filterTab,
                    onSelect = { filterTab = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (!viewingArchived && archivedChats.isNotEmpty() && !inSelectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewingArchived = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(palette.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(ExtraIcons.Archive, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${strings.archivedHeader} (${archivedChats.size})",
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
                HorizontalDivider(color = palette.divider, modifier = Modifier.padding(start = 68.dp))
            }

            if (contactRequests.isNotEmpty() && !inSelectionMode && !viewingArchived) {
                ContactRequestsBanner(
                    requests = contactRequests,
                    onAccept = onAcceptContactRequest,
                    onDecline = onDeclineContactRequest
                )
            }

            if (visibleChats.isEmpty() && !viewingArchived) {
                EmptyChatsState(onAddContact = onNewDirectChat)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 110.dp)
                ) {
                    items(visibleChats, key = { it.chatId }) { chat ->
                        val isSelected = chat.chatId in selectedIds
                        ChatSummaryRow(
                            chat = chat,
                            isSelected = isSelected,
                            onClick = {
                                if (inSelectionMode) {
                                    selectedIds = if (isSelected) selectedIds - chat.chatId else selectedIds + chat.chatId
                                } else {
                                    onSelectChat(chat)
                                }
                            },
                            onLongClick = {
                                selectedIds = if (isSelected) selectedIds - chat.chatId else selectedIds + chat.chatId
                            }
                        )
                        HorizontalDivider(
                            color = palette.divider,
                            modifier = Modifier.padding(start = 78.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    relayStatus: RelayStatus,
    viewingArchived: Boolean,
    archivedCount: Int,
    searchActive: Boolean,
    onBackFromArchive: () -> Unit,
    onTitleClick: () -> Unit,
    onToggleSearch: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    val titleColor = if (viewingArchived) palette.textPrimary else relayStatus.statusColor(palette)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (viewingArchived) {
            IconButton(onClick = onBackFromArchive) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.backBtn, tint = palette.textPrimary)
            }
            Text(
                text = "${strings.archivedHeader} ($archivedCount)",
                color = palette.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        } else {
            Text(
                text = strings.chatsTitle,
                color = titleColor,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onTitleClick)
            )
        }

        IconButton(onClick = onToggleSearch) {
            Icon(
                imageVector = if (searchActive) Icons.Default.Close else Icons.Default.Search,
                contentDescription = strings.searchPlaceholder,
                tint = palette.textPrimary
            )
        }
    }
}

@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    showMenu: Boolean,
    onToggleMenu: (Boolean) -> Unit,
    onClearSelection: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onClearHistory: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClearSelection) {
            Icon(Icons.Default.Close, contentDescription = strings.cancelBtn, tint = palette.textPrimary)
        }
        Text(
            text = "$selectedCount ${strings.selectedCountSuffix}",
            color = palette.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onPin) {
            Icon(ExtraIcons.Pin, contentDescription = "Pin", tint = palette.textPrimary)
        }
        IconButton(onClick = onMute) {
            Icon(ExtraIcons.NotificationsOff, contentDescription = "Mute", tint = palette.textPrimary)
        }
        IconButton(onClick = onArchive) {
            Icon(ExtraIcons.Archive, contentDescription = "Archive", tint = palette.textPrimary)
        }
        Box {
            IconButton(onClick = { onToggleMenu(true) }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = palette.textPrimary)
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { onToggleMenu(false) },
                modifier = Modifier.background(palette.surface)
            ) {
                DropdownMenuItem(
                    text = { Text(strings.selectAll, color = palette.textPrimary) },
                    onClick = { onToggleMenu(false); onSelectAll() }
                )
                DropdownMenuItem(
                    text = { Text(strings.clearHistory, color = palette.textPrimary) },
                    onClick = { onToggleMenu(false); onClearHistory() }
                )
                DropdownMenuItem(
                    text = { Text(strings.deleteChat, color = palette.error) },
                    onClick = { onToggleMenu(false); onDelete() }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatSummaryRow(
    chat: ChatSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = GhostTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) palette.selectionHighlight else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            AvatarView(
                title = chat.title,
                seed = chat.chatId,
                avatarBase64 = chat.avatarBase64,
                isGroup = chat.isGroup,
                size = 50.dp
            )
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(palette.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = chat.title,
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (chat.keyChangedWarning) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.Warning, contentDescription = null, tint = palette.error, modifier = Modifier.size(14.dp))
                    } else if (!chat.isGroup && chat.isVerified) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(AppIcons.VerifiedUser, contentDescription = null, tint = palette.connected, modifier = Modifier.size(14.dp))
                    }
                    if (chat.isMuted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(ExtraIcons.NotificationsOff, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(13.dp))
                    }
                }
                if (chat.lastMessageTime > 0L) {
                    Text(
                        text = GhostKeyUtils.formatClockTime(chat.lastMessageTime),
                        color = if (chat.unreadCount > 0 && !chat.isMuted) palette.accent else palette.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chat.lastMessageText,
                    color = palette.textSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (chat.isPinned) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(ExtraIcons.Pin, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(14.dp))
                }
                if (chat.unreadCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                            .clip(CircleShape)
                            .background(if (chat.isMuted) palette.textTertiary else palette.accent)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRequestsBanner(
    requests: List<ContactRequest>,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit
) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        requests.forEach { req ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(AppIcons.VerifiedUser, contentDescription = null, tint = palette.connected, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${strings.contactRequestsHeader}: ${req.alias}",
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = req.fingerprint.chunked(4).take(4).joinToString(" ") + "...",
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onDecline(req.dropId) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(strings.declineRequestBtn, color = palette.error, fontSize = 13.sp)
                    }
                    Button(
                        onClick = { onAccept(req.dropId) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.connected, contentColor = Color.White)
                    ) {
                        Text(strings.acceptRequestBtn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatsState(onAddContact: () -> Unit) {
    val palette = GhostTheme.colors
    val strings = GhostTheme.strings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp)
            .padding(bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(palette.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.Chat,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(38.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = strings.emptyChatsTitle,
            color = palette.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strings.emptyChatsSubtitle,
            color = palette.textSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        PrimaryActionButton(
            text = strings.addContactBtn,
            onClick = onAddContact,
            modifier = Modifier.widthIn(max = 240.dp)
        )
    }
}
