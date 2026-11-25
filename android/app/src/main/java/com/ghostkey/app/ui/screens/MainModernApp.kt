package com.ghostkey.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ghostkey.app.AppNavScreen
import com.ghostkey.app.MainViewModel
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.ui.dialogs.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainModernApp(
    viewModel: MainViewModel,
    onMicClick: () -> Unit,
    onEmergencyPanic: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val summaries by viewModel.chatSummaries.collectAsState()
    val activeMessages by viewModel.activeChatMessages.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAddDirectChatDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showSafetyCodeDialog by remember { mutableStateOf(false) }
    var showNewIdentityConfirm by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TelegramDrawer(
                userAlias = state.userAlias,
                myPgpKeys = state.myPgpKeys,
                relayStatus = state.relayStatus,
                artiState = state.artiState,
                onNavigateChats = {
                    viewModel.navigateTo(AppNavScreen.CHATS_LIST)
                    scope.launch { drawerState.close() }
                },
                onNavigateSettings = {
                    viewModel.navigateTo(AppNavScreen.SETTINGS)
                    scope.launch { drawerState.close() }
                },
                onCopyDropId = {
                    state.myPgpKeys?.dropId?.let { viewModel.copyToClipboard("Drop ID", it) }
                },
                onCopyFingerprint = {
                    state.myPgpKeys?.fingerprint?.let { viewModel.copyToClipboard("PGP Fingerprint", it) }
                },
                onCopyPublicKey = {
                    state.myPgpKeys?.publicKeyArmored?.let { viewModel.copyToClipboard("Armored Public Key", it) }
                },
                onShowMyQr = {
                    viewModel.setShowMyQrDialog(true)
                    scope.launch { drawerState.close() }
                },
                onPairContact = {
                    viewModel.setShowPairContactDialog(true)
                    scope.launch { drawerState.close() }
                },
                onOpenSecurityDiagnostics = {
                    viewModel.setShowSecurityDiagnostics(true)
                    scope.launch { drawerState.close() }
                },
                onEmergencyPanic = onEmergencyPanic
            )
        }
    ) {
        BackHandler(enabled = drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
        BackHandler(enabled = state.navScreen != AppNavScreen.CHATS_LIST && !drawerState.isOpen) {
            viewModel.navigateTo(AppNavScreen.CHATS_LIST)
        }

        when (state.navScreen) {
            AppNavScreen.CHATS_LIST -> {
                ChatsListScreen(
                    chats = summaries,
                    relayStatus = state.relayStatus,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSettings = { viewModel.navigateTo(AppNavScreen.SETTINGS) },
                    onSelectChat = { summary -> viewModel.selectChat(summary.chatId) },
                    onNewDirectChat = { showAddDirectChatDialog = true },
                    onNewGroupChat = { showCreateGroupDialog = true },
                    onShowMyQr = { viewModel.setShowMyQrDialog(true) },
                    onPairContactQr = { viewModel.setShowPairContactDialog(true) },
                    onOpenSecurityDiagnostics = { viewModel.setShowSecurityDiagnostics(true) }
                )
            }
            AppNavScreen.CHAT_VIEW -> {
                val activeGroup = state.activeChatGroup
                val activeContact = state.activeChatContact
                val title = activeGroup?.name ?: activeContact?.alias ?: "Encrypted Chat"
                val subtitle = if (activeGroup != null) {
                    "${activeGroup.members.size} members · AES-256 E2EE"
                } else {
                    "Peer ${(activeContact?.fingerprint ?: "").take(8)} · E2EE PGP"
                }
                val otherContacts = remember(state.contacts, activeContact) {
                    state.contacts.filter { it.dropId != activeContact?.dropId }
                }

                CleanChatScreen(
                    chatTitle = title,
                    chatSubtitle = subtitle,
                    isGroup = activeGroup != null,
                    messages = activeMessages,
                    playbackState = playbackState,
                    relayStatus = state.relayStatus,
                    isRecordingAudio = state.isRecordingAudio,
                    recordElapsedMs = state.recordElapsedMs,
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
                    disappearingTtlSeconds = activeContact?.disappearingTtlSeconds,
                    onSetDisappearingTimer = viewModel::setDisappearingTimerForActiveContact,
                    availableContacts = otherContacts,
                    onIntroduceContact = { contactToIntroduce ->
                        activeContact?.let { target -> viewModel.sendContactIntroduction(target, contactToIntroduce) }
                    },
                    onAcceptIntroduction = viewModel::addIntroducedContact
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
                    onBack = { viewModel.navigateTo(AppNavScreen.CHATS_LIST) },
                    onSaveSettings = viewModel::saveAllSettings,
                    onAutoSave = viewModel::onAutoSaveSettings,
                    onGenerateNewKey = { showNewIdentityConfirm = true },
                    onCopyKey = {
                        state.myPgpKeys?.let { viewModel.copyToClipboard("Public Key", it.publicKeyArmored) }
                    },
                    onVerifySafetyCode = { showSafetyCodeDialog = true },
                    duressPinConfigured = state.duressPinConfigured,
                    onSetDuressPin = viewModel::setDuressPin,
                    appPinConfigured = state.appPinConfigured,
                    onSetAppPin = viewModel::setAppPin
                )
            }
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
            isEphemeral = state.ephemeralMode,
            hasDuressPin = state.duressPinConfigured,
            myFingerprint = state.myPgpKeys?.fingerprint.orEmpty(),
            myDropId = state.myPgpKeys?.dropId.orEmpty(),
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
                Toast.makeText(context, "Contact paired & verified: ${contact.alias} 🛡️", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
