package com.ghostkey.app.ui.screens

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.ghostkey.app.AppNavScreen
import com.ghostkey.app.MainActivity
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.ui.dialogs.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActivity.MainModernApp() {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAddDirectChatDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showSafetyCodeDialog by remember { mutableStateOf(false) }
    var showNewIdentityConfirm by remember { mutableStateOf(false) }

    val playbackState by audioPlayerManager.playbackState.collectAsState()

    LaunchedEffect(isRecordingAudio) {
        if (isRecordingAudio) {
            val start = SystemClock.elapsedRealtime()
            while (isRecordingAudio) {
                recordElapsedMs = SystemClock.elapsedRealtime() - start
                delay(100)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TelegramDrawer(
                userAlias = userAlias,
                myPgpKeys = myPgpKeys,
                relayStatus = relayStatus.value,
                artiState = artiState,
                onNavigateChats = {
                    currentNavScreen = AppNavScreen.CHATS_LIST
                    scope.launch { drawerState.close() }
                },
                onNavigateSettings = {
                    currentNavScreen = AppNavScreen.SETTINGS
                    scope.launch { drawerState.close() }
                },
                onCopyDropId = {
                    myPgpKeys?.dropId?.let { copyToClipboard("Drop ID", it) }
                },
                onCopyFingerprint = {
                    myPgpKeys?.fingerprint?.let { copyToClipboard("PGP Fingerprint", it) }
                },
                onCopyPublicKey = {
                    myPgpKeys?.publicKeyArmored?.let { copyToClipboard("Armored Public Key", it) }
                },
                onShowMyQr = {
                    showMyQrDialog = true
                    scope.launch { drawerState.close() }
                },
                onPairContact = {
                    showPairContactDialog = true
                    scope.launch { drawerState.close() }
                },
                onOpenSecurityDiagnostics = {
                    showSecurityDiagnostics = true
                    scope.launch { drawerState.close() }
                },
                onEmergencyPanic = {
                    triggerEmergencyPanicWipe()
                }
            )
        }
    ) {
        BackHandler(enabled = drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }
        BackHandler(enabled = currentNavScreen != AppNavScreen.CHATS_LIST && !drawerState.isOpen) {
            currentNavScreen = AppNavScreen.CHATS_LIST
        }

        when (currentNavScreen) {
            AppNavScreen.CHATS_LIST -> {
                ChatsListScreen(
                    chats = computeChatSummaries(),
                    relayStatus = relayStatus.value,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSettings = { currentNavScreen = AppNavScreen.SETTINGS },
                    onSelectChat = { summary ->
                        activeChatContact = contacts.find { it.dropId == summary.chatId }
                        activeChatGroup = groups.find { it.id == summary.chatId }
                        currentNavScreen = AppNavScreen.CHAT_VIEW
                    },
                    onNewDirectChat = { showAddDirectChatDialog = true },
                    onNewGroupChat = { showCreateGroupDialog = true },
                    onShowMyQr = { showMyQrDialog = true },
                    onPairContactQr = { showPairContactDialog = true },
                    onOpenSecurityDiagnostics = { showSecurityDiagnostics = true }
                )
            }
            AppNavScreen.CHAT_VIEW -> {
                val title = activeChatGroup?.name ?: activeChatContact?.alias ?: "Encrypted Chat"
                val subtitle = if (activeChatGroup != null) {
                    "${activeChatGroup?.members?.size ?: 1} members · AES-256 E2EE"
                } else {
                    "Peer ${(activeChatContact?.fingerprint ?: "").take(8)} · E2EE PGP"
                }
                val activeChatId = activeChatGroup?.id ?: activeChatContact?.dropId ?: ""
                val chatMessages = messagesState.filter { it.chatId == activeChatId || (activeChatId.isBlank() && it.chatId.isBlank()) }

                CleanChatScreen(
                    chatTitle = title,
                    chatSubtitle = subtitle,
                    isGroup = activeChatGroup != null,
                    messages = chatMessages,
                    playbackState = playbackState,
                    relayStatus = relayStatus.value,
                    isRecordingAudio = isRecordingAudio,
                    recordElapsedMs = recordElapsedMs,
                    onBack = { currentNavScreen = AppNavScreen.CHATS_LIST },
                    onSendMessage = { text ->
                        sendMessageToActiveChat(text)
                    },
                    onSendMedia = { uri ->
                        sendMediaToActiveChat(uri)
                    },
                    onMicClick = { onMicClicked() },
                    onStopRecording = { onStopAudioRecording() },
                    onCancelRecording = { onCancelAudioRecording() },
                    onAudioPlayPause = { msg ->
                        if (msg.audioBytes != null) {
                            audioPlayerManager.play(msg.id, msg.audioBytes)
                        }
                    },
                    onAudioSeek = { msg, frac ->
                        audioPlayerManager.seekTo(msg.id, frac)
                    },
                    onManualDecrypt = { msg ->
                        decryptMessage(msg, msg.chatId.ifBlank { recipientDropId })
                    },
                    onOpenSafetyCode = { showSafetyCodeDialog = true },
                    onOpenSettings = { currentNavScreen = AppNavScreen.SETTINGS },
                    disappearingTtlSeconds = getDisappearingTtlForChat(activeChatId),
                    onSetDisappearingTimer = { ttl ->
                        activeChatContact?.let { setDisappearingTimerForContact(it.dropId, ttl) }
                    },
                    availableContacts = contacts.filter { it.dropId != activeChatContact?.dropId },
                    onIntroduceContact = { contactToIntroduce ->
                        activeChatContact?.let { target ->
                            sendContactIntroduction(target, contactToIntroduce)
                        }
                    },
                    onAcceptIntroduction = { alias, dropId, armoredKey, safetyCode ->
                        addIntroducedContact(alias, dropId, armoredKey, safetyCode)
                    }
                )
            }
            AppNavScreen.SETTINGS -> {
                SettingsScreen(
                    initialRelayUrl = relayServerUrl,
                    initialUseTorSocks = useTorSocks,
                    initialKeepAlive = keepAlive,
                    initialEphemeralMode = ephemeralMode,
                    initialLockEnabled = lockEnabled,
                    userAlias = userAlias,
                    myPgpKeys = myPgpKeys,
                    onBack = { currentNavScreen = AppNavScreen.CHATS_LIST },
                    onSaveSettings = { url, tor, keep, eph, lock, alias ->
                        saveAllSettings(url, tor, keep, eph, lock, alias)
                    },
                    onAutoSave = { url, alias ->
                        onAutoSaveSettings(url, alias)
                    },
                    onGenerateNewKey = { showNewIdentityConfirm = true },
                    onCopyKey = { myPgpKeys?.let { copyKeyToClipboard(it.publicKeyArmored) } },
                    onVerifySafetyCode = { showSafetyCodeDialog = true },
                    duressPinConfigured = !duressPinHash.isNullOrBlank(),
                    onSetDuressPin = { pin -> setDuressPin(pin) },
                    appPinConfigured = !appPinHash.isNullOrBlank(),
                    onSetAppPin = { pin -> setAppPin(pin) }
                )
            }
        }
    }

    if (showAddDirectChatDialog) {
        AddDirectChatDialog(
            onDismiss = { showAddDirectChatDialog = false },
            onAddContact = { contact ->
                showAddDirectChatDialog = false
                if (!contacts.any { it.dropId == contact.dropId }) {
                    contacts.add(contact)
                    persistIdentity()
                }
                activeChatContact = contact
                activeChatGroup = null
                currentNavScreen = AppNavScreen.CHAT_VIEW
            }
        )
    }

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            contacts = contacts.toList(),
            onDismiss = { showCreateGroupDialog = false },
            onCreateGroup = { group ->
                showCreateGroupDialog = false
                groups.add(group)
                torClient.addSubscription(group.groupDropId, group.groupReadToken)
                persistIdentity()
                inviteMembersToGroup(group)
                activeChatGroup = group
                activeChatContact = null
                currentNavScreen = AppNavScreen.CHAT_VIEW
            }
        )
    }

    if (showSafetyCodeDialog) {
        val myFp = myPgpKeys?.fingerprint ?: ""
        val peerFp = activeChatContact?.fingerprint ?: recipientFingerprint
        val activeContact = activeChatContact
        SafetyCodeDialog(
            safetyNumber = if (myPgpKeys != null && peerFp.isNotBlank()) {
                StandalonePgpEngine.generateSafetyCode(myPgpKeys!!.fingerprint, peerFp)
            } else {
                "NO PEER SELECTED"
            },
            recipientFingerprint = peerFp,
            isPeerVerified = activeContact?.isVerified ?: false,
            onVerificationChanged = { verified ->
                if (activeContact != null) {
                    val idx = contacts.indexOfFirst { it.dropId == activeContact.dropId }
                    if (idx >= 0) {
                        contacts[idx] = contacts[idx].copy(isVerified = verified)
                        activeChatContact = contacts[idx]
                        persistIdentity()
                    }
                }
            },
            onDismiss = { showSafetyCodeDialog = false }
        )
    }

    if (showSecurityDiagnostics) {
        SecurityStatusDialog(
            relayStatus = relayStatus.value,
            artiState = artiState,
            relayUrl = relayServerUrl,
            lanPeersCount = localLanMesh?.activePeersCount ?: 0,
            isEphemeral = ephemeralMode,
            hasDuressPin = !duressPinHash.isNullOrBlank(),
            myFingerprint = myPgpKeys?.fingerprint ?: "",
            myDropId = myPgpKeys?.dropId ?: "",
            onDismiss = { showSecurityDiagnostics = false }
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

    if (showMyQrDialog) {
        val keys = myPgpKeys
        if (keys != null) {
            MyQrCodeDialog(
                myAlias = userAlias,
                myDropId = keys.dropId,
                myFingerprint = keys.fingerprint,
                myPublicKeyArmored = keys.publicKeyArmored,
                onDismiss = { showMyQrDialog = false }
            )
        } else {
            Toast.makeText(this, "Generating PGP identity, please wait...", Toast.LENGTH_SHORT).show()
            showMyQrDialog = false
        }
    }

    if (showPairContactDialog) {
        PairContactDialog(
            myFingerprint = myPgpKeys?.fingerprint ?: "",
            onDismiss = { showPairContactDialog = false },
            onContactVerified = { contact ->
                showPairContactDialog = false
                val existingIdx = contacts.indexOfFirst { it.dropId == contact.dropId }
                if (existingIdx >= 0) {
                    contacts[existingIdx] = contact
                } else {
                    contacts.add(contact)
                }
                persistIdentity()
                activeChatContact = contact
                activeChatGroup = null
                currentNavScreen = AppNavScreen.CHAT_VIEW
                Toast.makeText(this, "Contact paired & verified: ${contact.alias} 🛡️", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
