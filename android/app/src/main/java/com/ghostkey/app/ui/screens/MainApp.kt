package com.ghostkey.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ghostkey.app.AppNavScreen
import com.ghostkey.app.MainViewModel
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.ui.components.NavigationIsland
import com.ghostkey.app.ui.dialogs.*
import com.ghostkey.app.ui.screens.chats.ChatViewScreen
import com.ghostkey.app.ui.screens.chats.ChatsListScreen
import com.ghostkey.app.ui.screens.profile.ProfileScreen
import com.ghostkey.app.ui.screens.settings.SettingsScreen
import com.ghostkey.app.ui.theme.GhostTheme

/**
 * Root authenticated UI shell hosting Chats, Chat View, Settings, Profile,
 * floating NavigationIsland, and modal security dialogs.
 */
@Composable
internal fun MainApp(
    viewModel: MainViewModel,
    onMicClick: () -> Unit,
    onEmergencyPanic: () -> Unit
) {
    val context = LocalContext.current
    val strings = GhostTheme.strings
    val state by viewModel.uiState.collectAsState()
    val summaries by viewModel.chatSummaries.collectAsState()
    val activeMessages by viewModel.activeChatMessages.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    var showAddDirectChatDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showSafetyCodeDialog by remember { mutableStateOf(false) }
    var showNewIdentityConfirm by remember { mutableStateOf(false) }
    var showOfflineShareDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = state.navScreen != AppNavScreen.CHATS_LIST) {
        viewModel.navigateTo(AppNavScreen.CHATS_LIST)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (state.navScreen) {
            AppNavScreen.CHATS_LIST -> {
                ChatsListScreen(
                    chats = summaries,
                    relayStatus = state.relayStatus,
                    contactRequests = state.contactRequests,
                    onSelectChat = { summary -> viewModel.selectChat(summary.chatId) },
                    onNewDirectChat = { showAddDirectChatDialog = true },
                    onQuickPairClick = { viewModel.openQuickPairDialog(0) },
                    onPairContactQr = { viewModel.setShowPairContactDialog(true) },
                    onNewGroupChat = { showCreateGroupDialog = true },
                    onAcceptContactRequest = viewModel::acceptContactRequest,
                    onDeclineContactRequest = viewModel::declineContactRequest,
                    onOpenSecurityDiagnostics = { viewModel.setShowSecurityDiagnostics(true) },
                    onTogglePinChats = viewModel::togglePinChats,
                    onToggleMuteChats = viewModel::toggleMuteChats,
                    onToggleArchiveChats = viewModel::toggleArchiveChats,
                    onClearChatsHistory = viewModel::clearChatsHistory,
                    onDeleteChats = viewModel::deleteChats
                )
            }
            AppNavScreen.CHAT_VIEW -> {
                val activeGroup = state.activeChatGroup
                val activeContact = state.activeChatContact
                val activeChatId = activeGroup?.id ?: activeContact?.dropId ?: ""
                val title = activeGroup?.name ?: activeContact?.alias ?: strings.encryptedChatDefaultTitle
                val subtitle = if (activeGroup != null) {
                    "${activeGroup.members.size} ${strings.membersSuffix} · ${strings.groupSessionSubtitle}"
                } else {
                    "${(activeContact?.fingerprint ?: "").take(8)} · ${strings.directSessionSubtitle}"
                }
                val otherContacts = remember(state.contacts, activeContact) {
                    state.contacts.filter { it.dropId != activeContact?.dropId }
                }

                ChatViewScreen(
                    chatId = activeChatId,
                    chatTitle = title,
                    chatSubtitle = subtitle,
                    avatarBase64 = activeContact?.avatarBase64,
                    isGroup = activeGroup != null,
                    keyChangedWarning = activeContact?.keyChangedWarning == true,
                    messages = activeMessages,
                    playbackState = playbackState,
                    isRecordingAudio = state.isRecordingAudio,
                    recordElapsedMs = state.recordElapsedMs,
                    disappearingTtlSeconds = activeContact?.disappearingTtlSeconds,
                    availableContacts = otherContacts,
                    onBack = { viewModel.navigateTo(AppNavScreen.CHATS_LIST) },
                    onSendMessage = viewModel::sendMessageToActiveChat,
                    onSendMedia = viewModel::sendMediaToActiveChat,
                    onMicClick = onMicClick,
                    onStopRecording = viewModel::onStopAudioRecording,
                    onCancelRecording = viewModel::onCancelAudioRecording,
                    onAudioPlayPause = viewModel::toggleAudioPlayback,
                    onAudioSeek = viewModel::seekAudio,
                    onManualDecrypt = { msg ->
                        viewModel.decryptMessage(msg, msg.chatId.ifBlank { state.myPgpKeys?.dropId.orEmpty() })
                    },
                    onOpenSafetyCode = { showSafetyCodeDialog = true },
                    onDismissKeyWarning = {
                        activeContact?.dropId?.let(viewModel::dismissKeyChangedWarning)
                    },
                    onSetDisappearingTimer = viewModel::setDisappearingTimerForActiveContact,
                    onIntroduceContact = { target ->
                        activeContact?.let { current -> viewModel.sendContactIntroduction(current, target) }
                    },
                    onAcceptIntroduction = viewModel::addIntroducedContact,
                    onClearHistory = {
                        if (activeChatId.isNotBlank()) viewModel.clearChatHistory(activeChatId)
                    },
                    onClearForBoth = {
                        activeContact?.let(viewModel::clearChatForBoth)
                    },
                    onDeleteOrLeaveChat = {
                        if (activeGroup != null) viewModel.leaveAndDeleteGroup(activeGroup.id)
                        else if (activeContact != null) viewModel.deleteContact(activeContact.dropId)
                    }
                )
            }
            AppNavScreen.SETTINGS -> {
                SettingsScreen(
                    initialRelayUrl = state.relayServerUrl,
                    initialUseTorSocks = state.useTorSocks,
                    initialKeepAlive = state.keepAlive,
                    initialEphemeralMode = state.ephemeralMode,
                    initialLockEnabled = state.lockEnabled,
                    userAlias = state.userAlias,
                    myPgpKeys = state.myPgpKeys,
                    appPinConfigured = state.appPinConfigured,
                    duressPinConfigured = state.duressPinConfigured,
                    offlineBleMeshEnabled = state.offlineBleMeshEnabled,
                    offlineWifiDirectEnabled = state.offlineWifiDirectEnabled,
                    allowContactRequests = state.allowContactRequests,
                    language = state.language,
                    themeMode = state.themeMode,
                    relayStatus = state.relayStatus,
                    relayErrorDetail = state.relayErrorDetail,
                    onLanguageChange = viewModel::setLanguage,
                    onThemeModeChange = viewModel::setThemeMode,
                    onToggleAllowContactRequests = viewModel::setAllowContactRequests,
                    onSaveSettings = viewModel::saveAllSettings,
                    onAutoSave = viewModel::onAutoSaveSettings,
                    onSetAppPin = viewModel::setAppPin,
                    onSetDuressPin = viewModel::setDuressPin,
                    onCopyKey = {
                        state.myPgpKeys?.let { viewModel.copyToClipboard("Public Key", it.publicKeyArmored) }
                    },
                    onGenerateNewKey = { showNewIdentityConfirm = true },
                    onExportBackup = viewModel::exportEncryptedBackup,
                    onImportBackup = viewModel::importEncryptedBackup,
                    onToggleBleMesh = viewModel::setOfflineBleMeshEnabled,
                    onToggleWifiDirect = viewModel::setOfflineWifiDirectEnabled,
                    onOpenOfflineShare = { showOfflineShareDialog = true }
                )
            }
            AppNavScreen.PROFILE -> {
                ProfileScreen(
                    userAlias = state.userAlias,
                    myAvatarBase64 = state.myAvatarBase64,
                    myPgpKeys = state.myPgpKeys,
                    onSaveAlias = { newAlias -> viewModel.onAutoSaveSettings(state.relayServerUrl, newAlias) },
                    onPickAvatarUri = viewModel::setProfilePictureFromUri,
                    onCopyDropId = { state.myPgpKeys?.dropId?.let { viewModel.copyToClipboard("Drop ID", it) } },
                    onCopyFingerprint = { state.myPgpKeys?.fingerprint?.let { viewModel.copyToClipboard("Fingerprint", it) } },
                    onCopyPublicKey = { state.myPgpKeys?.publicKeyArmored?.let { viewModel.copyToClipboard("Public Key", it) } },
                    onShowMyQr = { viewModel.setShowMyQrDialog(true) },
                    onPairContactQr = { viewModel.setShowPairContactDialog(true) },
                    onOpenOfflineShare = { showOfflineShareDialog = true },
                    onEmergencyPanic = onEmergencyPanic
                )
            }
        }

        if (state.navScreen != AppNavScreen.CHAT_VIEW) {
            NavigationIsland(
                currentScreen = state.navScreen,
                onSelect = viewModel::navigateTo,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showAddDirectChatDialog) {
        AddDirectChatDialog(
            onDismiss = { showAddDirectChatDialog = false },
            onAddContact = { contact ->
                showAddDirectChatDialog = false
                viewModel.addOrOpenContact(contact)
            }
        )
    }

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            contacts = state.contacts,
            onDismiss = { showCreateGroupDialog = false },
            onCreateGroup = { group ->
                showCreateGroupDialog = false
                viewModel.createGroup(group)
            }
        )
    }

    if (showSafetyCodeDialog) {
        val myKeys = state.myPgpKeys
        val activeContact = state.activeChatContact
        val peerFp = activeContact?.fingerprint.orEmpty()
        SafetyCodeDialog(
            safetyNumber = if (myKeys != null && peerFp.isNotBlank()) {
                StandalonePgpEngine.generateSafetyCode(myKeys.fingerprint, peerFp)
            } else {
                "NO PEER SELECTED"
            },
            recipientFingerprint = peerFp,
            isPeerVerified = activeContact?.isVerified ?: false,
            onVerificationChanged = { verified ->
                activeContact?.let { viewModel.setContactVerified(it.dropId, verified) }
            },
            onDismiss = { showSafetyCodeDialog = false }
        )
    }

    if (state.showSecurityDiagnostics) {
        SecurityStatusDialog(
            relayStatus = state.relayStatus,
            artiState = state.artiState,
            lanPeersCount = state.lanPeersCount,
            blePeersCount = state.blePeersCount,
            wifiDirectPeersCount = state.wifiDirectPeersCount,
            isEphemeral = state.ephemeralMode,
            hasDuressPin = state.duressPinConfigured,
            myFingerprint = state.myPgpKeys?.fingerprint.orEmpty(),
            myDropId = state.myPgpKeys?.dropId.orEmpty(),
            relayErrorDetail = state.relayErrorDetail,
            onDismiss = { viewModel.setShowSecurityDiagnostics(false) }
        )
    }

    if (showNewIdentityConfirm) {
        NewIdentityConfirmDialog(
            onDismiss = { showNewIdentityConfirm = false },
            onConfirm = {
                showNewIdentityConfirm = false
                viewModel.generateFreshKeys()
            }
        )
    }

    val myKeysForQr = state.myPgpKeys
    if (state.showMyQrDialog && myKeysForQr != null) {
        MyQrCodeDialog(
            myAlias = state.userAlias,
            myDropId = myKeysForQr.dropId,
            myFingerprint = myKeysForQr.fingerprint,
            myPublicKeyArmored = myKeysForQr.publicKeyArmored,
            onDismiss = { viewModel.setShowMyQrDialog(false) }
        )
    }

    if (state.showPairContactDialog) {
        PairContactDialog(
            myFingerprint = state.myPgpKeys?.fingerprint.orEmpty(),
            onDismiss = { viewModel.setShowPairContactDialog(false) },
            onContactVerified = { contact ->
                viewModel.setShowPairContactDialog(false)
                viewModel.addOrOpenContact(contact)
                viewModel.sendContactRequestTo(contact)
                Toast.makeText(context, "Contact verified: ${contact.alias}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (state.quickPairState.isOpen) {
        QuickPairDialog(
            state = state.quickPairState,
            myFingerprint = state.myPgpKeys?.fingerprint.orEmpty(),
            onSelectTab = viewModel::selectQuickPairTab,
            onGenerateInvite = viewModel::generateRendezvousInvite,
            onRedeemInput = viewModel::redeemRendezvousInput,
            onConfirmPreview = viewModel::confirmRendezvousPreview,
            onCopyText = viewModel::copyToClipboard,
            onDismiss = viewModel::closeQuickPairDialog
        )
    }

    if (showOfflineShareDialog) {
        OfflineShareDialog(
            state = state.offlineShareState,
            onStartSharing = viewModel::startOfflineApkSharing,
            onStopSharing = viewModel::stopOfflineApkSharing,
            onDismiss = { showOfflineShareDialog = false }
        )
    }
}
