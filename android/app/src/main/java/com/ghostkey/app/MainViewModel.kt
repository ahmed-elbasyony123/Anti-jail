package com.ghostkey.app

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostkey.app.audio.AudioSessionCoordinator
import com.ghostkey.app.audio.PlaybackState
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.crypto.WireMessage
import com.ghostkey.app.data.*
import com.ghostkey.app.net.*
import com.ghostkey.app.net.share.OfflineShareState
import com.ghostkey.app.notify.PushNotificationManager
import com.ghostkey.app.security.SecurityLockManager
import com.ghostkey.app.service.TorForegroundService
import com.ghostkey.app.util.GhostKeyUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class AppNavScreen { CHATS_LIST, CHAT_VIEW, SETTINGS, PROFILE }

enum class AppLanguage(val code: String) {
    EN("en"),
    AR("ar");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            if (code.equals("ar", ignoreCase = true)) AR else EN
    }
}

enum class AppThemeMode(val code: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        fun fromCode(code: String?): AppThemeMode = when (code?.lowercase()) {
            "dark" -> DARK
            "system" -> SYSTEM
            else -> LIGHT
        }
    }
}

sealed interface RelayTestState {
    data object Idle : RelayTestState
    data object Testing : RelayTestState
    data class Success(val latencyMs: Long) : RelayTestState
    data class Failed(val reason: String) : RelayTestState
}

internal data class MainUiState(
    val navScreen: AppNavScreen = AppNavScreen.CHATS_LIST,
    val activeChatContact: Contact? = null,
    val activeChatGroup: GroupChat? = null,
    val contacts: List<Contact> = emptyList(),
    val groups: List<GroupChat> = emptyList(),
    val userAlias: String = "",
    val myAvatarBase64: String? = null,
    val myAvatarHash: String? = null,
    val myPgpKeys: PgpKeyPairArmored? = null,
    val isGeneratingKey: Boolean = false,
    val relayServerUrl: String = "",
    val useTorSocks: Boolean = true,
    val keepAlive: Boolean = true,
    val ephemeralMode: Boolean = false,
    val lockEnabled: Boolean = false,
    val isLocked: Boolean = false,
    val isOnboarding: Boolean = false,
    val appPinConfigured: Boolean = false,
    val duressPinConfigured: Boolean = false,
    val relayStatus: RelayStatus = RelayStatus.DISCONNECTED,
    val relayErrorDetail: String? = null,
    val relayTestState: RelayTestState = RelayTestState.Idle,
    val artiState: ArtiTorState = ArtiTorState.Idle,
    val isRecordingAudio: Boolean = false,
    val recordElapsedMs: Long = 0L,
    val showMyQrDialog: Boolean = false,
    val showPairContactDialog: Boolean = false,
    val showSecurityDiagnostics: Boolean = false,
    val lanPeersCount: Int = 0,
    val blePeersCount: Int = 0,
    val wifiDirectPeersCount: Int = 0,
    val offlineBleMeshEnabled: Boolean = false,
    val offlineWifiDirectEnabled: Boolean = false,
    val offlineShareState: OfflineShareState = OfflineShareState(),
    val language: AppLanguage = AppLanguage.EN,
    val themeMode: AppThemeMode = AppThemeMode.LIGHT
)

/**
 * Slim ViewModel façade delegating Tor, Audio, Vault, Security Lock, and Messaging to dedicated coordinators.
 */
internal class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val identityRepo = IdentityRepository(appContext)
    private val lockManager = SecurityLockManager(appContext, identityRepo)
    private val vault = IdentityAndContactsVault(appContext.contentResolver, identityRepo)
    private val torClient = TorRelayClient()
    private val audioCoordinator = AudioSessionCoordinator(appContext, viewModelScope)

    private val transport: GhostTransport = GhostTransport(appContext, torClient, ArtiTorManager) { senderDropId, ciphertext ->
        messagingService.insertMeshIncoming(senderDropId, ciphertext)
            ?.let { decryptMessage(it, senderDropId, fromRelay = false) }
    }
    private val messagingService: MessagingService = MessagingService(appContext.contentResolver, transport)
    private val torCoordinator: TorConnectionCoordinator

    private var isForeground = false
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val lastReadByChat = MutableStateFlow<Map<String, Long>>(emptyMap())

    val messages: StateFlow<List<ChatMessage>> = messagingService.messages
    val playbackState: StateFlow<PlaybackState> = audioCoordinator.playbackState

    val chatSummaries: StateFlow<List<ChatSummary>> = combine(_uiState, messagingService.messages, lastReadByChat) { state, msgs, readMap ->
        messagingService.buildChatSummaries(state.contacts, state.groups, msgs, readMap)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeChatMessages: StateFlow<List<ChatMessage>> = combine(_uiState, messagingService.messages) { state, msgs ->
        val activeChatId = state.activeChatGroup?.id ?: state.activeChatContact?.dropId ?: ""
        if (activeChatId.isBlank()) emptyList() else msgs.filter { it.chatId == activeChatId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        val prefs = identityRepo.readPreferences(OrbotPortWatcher.DEFAULT_SOCKS_PORT)
        lockManager.loadFromPrefs(prefs.appPinHash, prefs.duressPinHash, prefs.pinSaltHex)

        val shouldOnboard = !prefs.hasCompletedOnboarding && !identityRepo.hasStoredIdentity()
        val shouldLock = !shouldOnboard && prefs.lockEnabled && !prefs.ephemeralMode

        _uiState.value = MainUiState(
            relayServerUrl = prefs.relayUrl,
            useTorSocks = prefs.useTorSocks,
            keepAlive = prefs.keepAlive,
            ephemeralMode = prefs.ephemeralMode,
            lockEnabled = prefs.lockEnabled,
            userAlias = prefs.userAlias,
            isOnboarding = shouldOnboard,
            isLocked = shouldLock,
            appPinConfigured = lockManager.hasAppPin,
            duressPinConfigured = lockManager.hasDuressPin,
            offlineBleMeshEnabled = prefs.offlineBleMeshEnabled,
            offlineWifiDirectEnabled = prefs.offlineWifiDirectEnabled,
            language = AppLanguage.fromCode(prefs.languageCode),
            themeMode = AppThemeMode.fromCode(prefs.themeMode)
        )

        torCoordinator = TorConnectionCoordinator(
            appContext = appContext,
            scope = viewModelScope,
            transport = transport,
            identityRepo = identityRepo,
            initialSocksPort = prefs.orbotSocksPort,
            getParams = {
                val st = _uiState.value
                TorNetworkParams(
                    relayServerUrl = st.relayServerUrl,
                    useTorSocks = st.useTorSocks,
                    keys = st.myPgpKeys,
                    groups = st.groups,
                    offlineBleMeshEnabled = st.offlineBleMeshEnabled,
                    offlineWifiDirectEnabled = st.offlineWifiDirectEnabled
                )
            },
            onArtiState = { st -> _uiState.value = _uiState.value.copy(artiState = st) },
            onRelayState = { status, err -> _uiState.value = _uiState.value.copy(relayStatus = status, relayErrorDetail = err) },
            onRelayMessage = { record ->
                viewModelScope.launch {
                    val msg = messagingService.insertPendingIncoming(record) ?: return@launch
                    decryptMessage(msg, record.dropId, fromRelay = true)
                }
            },
            onRelayConnected = { viewModelScope.launch { messagingService.flushOutbox() } }
        )

        PushNotificationManager.init(appContext)
        observeTransportAndAudio()
        torCoordinator.start()
        loadOrCreateIdentity(shouldOnboard, shouldLock)
        if (prefs.keepAlive) TorForegroundService.start(appContext)
        observeForegroundServiceState()
        startDisappearingMessagesReaper()
    }

    override fun onCleared() {
        super.onCleared()
        torCoordinator.stop()
        transport.close()
        audioCoordinator.close()
    }

    private fun observeTransportAndAudio() {
        viewModelScope.launch {
            transport.snapshot.collect { snap ->
                _uiState.value = _uiState.value.copy(
                    lanPeersCount = snap.lanPeerCount,
                    blePeersCount = snap.bleMesh.connectedPeers,
                    wifiDirectPeersCount = snap.wifiDirect.peers.size,
                    offlineShareState = snap.offlineShare,
                    relayErrorDetail = snap.relayErrorDetail
                )
            }
        }
        viewModelScope.launch {
            audioCoordinator.recordingState.collect { rec ->
                _uiState.value = _uiState.value.copy(isRecordingAudio = rec.isRecording, recordElapsedMs = rec.elapsedMs)
            }
        }
    }

    private fun loadOrCreateIdentity(shouldOnboard: Boolean, shouldLock: Boolean) {
        if (!shouldOnboard && !shouldLock) {
            bootstrapIdentity()
        } else if (shouldLock && identityRepo.hasStoredIdentity()) {
            viewModelScope.launch {
                runCatching { identityRepo.loadIdentity() }.onSuccess { stored ->
                    lockManager.mergeFromStoredIdentity(stored.appPinHash, stored.duressPinHash, stored.pinSaltHex)
                    _uiState.value = _uiState.value.copy(
                        appPinConfigured = lockManager.hasAppPin,
                        duressPinConfigured = lockManager.hasDuressPin
                    )
                }
            }
        }
    }

    fun onForeground() {
        isForeground = true
        MainActivity.isExternalPickerActive = false
        PushNotificationManager.clearMessageNotifications(appContext)
    }

    fun onPauseActivity() {
        isForeground = false
        if (_uiState.value.isRecordingAudio) onCancelAudioRecording()
        persistCurrentStateSync()
    }

    fun onStopActivity() {
        val state = _uiState.value
        if (!MainActivity.isExternalPickerActive && state.lockEnabled && !state.ephemeralMode && !state.isOnboarding) {
            _uiState.value = state.copy(isLocked = true)
        }
        persistCurrentStateSync()
    }

    private fun persistCurrentStateSync() {
        val state = _uiState.value
        if (!state.ephemeralMode && !state.isOnboarding) {
            identityRepo.saveRelayAndAliasPrefs(state.relayServerUrl, state.userAlias, ephemeralMode = false, markOnboarded = true)
            buildIdentitySnapshot()?.let { identityRepo.saveIdentitySync(it, ephemeralMode = false) }
        }
    }

    fun onAppUnlocked() {
        _uiState.value = _uiState.value.copy(isLocked = false)
        if (_uiState.value.myPgpKeys == null) bootstrapIdentity()
    }

    fun onDeviceLockUnavailable(): Boolean {
        return if (!lockManager.hasAppPin) {
            _uiState.value = _uiState.value.copy(lockEnabled = false, isLocked = false)
            identityRepo.saveLockEnabled(false)
            toast("No device screen lock is set, so the app lock was turned off", Toast.LENGTH_LONG)
            bootstrapIdentity()
            true
        } else {
            toast("Device lock screen not configured. Please use your App PIN.", Toast.LENGTH_SHORT)
            false
        }
    }

    fun verifyPin(pin: String): Boolean = lockManager.verifyPin(pin)
    fun isDuressPin(pin: String): Boolean = lockManager.isDuressPin(pin)

    fun completeOnboarding(
        alias: String,
        appPin: String?,
        duressPin: String?,
        ephemeralMode: Boolean,
        relayUrl: String = ""
    ) {
        val cleanAlias = GhostKeyUtils.sanitizeAlias(alias)
        val cleanRelay = GhostKeyUtils.sanitizeRelayUrl(relayUrl)
        val (newAppHash, newDuressHash, newSalt) = runCatching {
            lockManager.configureOnboardingPins(appPin, duressPin, ephemeralMode)
        }.getOrElse { err ->
            toast("PIN setup error: ${err.message ?: "failed"}", Toast.LENGTH_LONG)
            Triple(null, null, null)
        }
        val lockOn = !ephemeralMode && newAppHash != null
        identityRepo.saveOnboardingCompletion(cleanAlias, ephemeralMode, lockOn, newAppHash, newDuressHash, newSalt)
        if (!ephemeralMode && cleanRelay.isNotBlank()) {
            identityRepo.saveRelayAndAliasPrefs(cleanRelay, cleanAlias, ephemeralMode = false, markOnboarded = true)
        }
        val needTor = torCoordinator.requiresTor(_uiState.value.useTorSocks, cleanRelay)
        _uiState.value = _uiState.value.copy(
            userAlias = cleanAlias,
            relayServerUrl = cleanRelay,
            useTorSocks = needTor,
            ephemeralMode = ephemeralMode,
            lockEnabled = lockOn,
            appPinConfigured = !newAppHash.isNullOrBlank(),
            duressPinConfigured = !newDuressHash.isNullOrBlank(),
            isGeneratingKey = true
        )
        torCoordinator.applyNetworkConfig()
        torCoordinator.ensureTorEngineRunning()
        generateFreshKeys(cleanAlias, exitOnboardingWhenDone = true)
    }

    fun setLanguage(language: AppLanguage) {
        _uiState.value = _uiState.value.copy(language = language)
        identityRepo.saveUiPreferences(language.code, _uiState.value.themeMode.code)
    }

    fun setThemeMode(mode: AppThemeMode) {
        _uiState.value = _uiState.value.copy(themeMode = mode)
        identityRepo.saveUiPreferences(_uiState.value.language.code, mode.code)
    }

    fun resetRelayTest() {
        _uiState.value = _uiState.value.copy(relayTestState = RelayTestState.Idle)
    }

    fun testRelayConnection(rawUrl: String) {
        val cleanUrl = GhostKeyUtils.sanitizeRelayUrl(rawUrl)
        if (cleanUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(relayTestState = RelayTestState.Failed("Relay URL is empty"))
            return
        }
        _uiState.value = _uiState.value.copy(relayTestState = RelayTestState.Testing)
        viewModelScope.launch {
            val needTor = torCoordinator.requiresTor(_uiState.value.useTorSocks, cleanUrl)
            if (needTor && ArtiTorManager.isAvailable && _uiState.value.artiState !is ArtiTorState.Connected) {
                torCoordinator.ensureTorEngineRunning()
                // Wait briefly for Tor circuit if testing an .onion address during onboarding
                var waited = 0
                while (_uiState.value.artiState !is ArtiTorState.Connected && waited < 15) {
                    delay(1_000L)
                    waited++
                }
            }
            torClient.pingRelay(cleanUrl)
                .onSuccess { latency ->
                    _uiState.value = _uiState.value.copy(relayTestState = RelayTestState.Success(latency))
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        relayTestState = RelayTestState.Failed(err.message ?: "Unreachable")
                    )
                }
        }
    }

    private fun observeForegroundServiceState() {
        viewModelScope.launch {
            var sawRunning = false
            TorForegroundService.isRunning.collect { running ->
                if (running) sawRunning = true
                else if (sawRunning && _uiState.value.keepAlive) _uiState.value = _uiState.value.copy(keepAlive = false)
            }
        }
    }

    private fun bootstrapIdentity() {
        val st = _uiState.value
        if (st.ephemeralMode || !identityRepo.hasStoredIdentity()) {
            generateFreshKeys()
            return
        }
        viewModelScope.launch {
            try {
                applyLoadedIdentity(identityRepo.loadIdentity())
            } catch (_: Exception) {
                identityRepo.wipeStorageOnly()
                toast("The saved identity could not be read, so a new one was created", Toast.LENGTH_LONG)
                generateFreshKeys()
            }
        }
    }

    private fun applyLoadedIdentity(stored: StoredIdentity) {
        val prefs = identityRepo.readPreferences(OrbotPortWatcher.DEFAULT_SOCKS_PORT)
        val resolvedAlias = prefs.userAlias.ifBlank { stored.userAlias }
        val resolvedRelay = prefs.relayUrl.ifBlank { stored.relayUrl }
        val loadedContacts = stored.contacts.toMutableList()
        stored.peer?.let { peer ->
            if (loadedContacts.none { it.dropId == peer.dropId }) {
                loadedContacts.add(Contact(peer.dropId, "Peer ${peer.fingerprint.take(6)}", peer.dropId, peer.publicKeyArmored, peer.fingerprint))
            }
        }
        lockManager.mergeFromStoredIdentity(stored.appPinHash, stored.duressPinHash, stored.pinSaltHex)

        _uiState.value = _uiState.value.copy(
            myPgpKeys = stored.mine,
            userAlias = resolvedAlias,
            relayServerUrl = resolvedRelay,
            myAvatarBase64 = stored.myAvatarBase64,
            myAvatarHash = stored.myAvatarHash,
            contacts = loadedContacts,
            groups = stored.groups,
            appPinConfigured = lockManager.hasAppPin,
            duressPinConfigured = lockManager.hasDuressPin
        )
        torCoordinator.applyNetworkConfig()
        torCoordinator.ensureTorEngineRunning()
        val st = _uiState.value
        torCoordinator.connectOrAwaitTor(stored.mine, stored.groups, st.offlineBleMeshEnabled, st.offlineWifiDirectEnabled)
    }

    private fun buildIdentitySnapshot(): StoredIdentity? {
        val st = _uiState.value
        val mine = st.myPgpKeys ?: return null
        val firstPeer = st.contacts.firstOrNull()?.let { PeerIdentity(it.publicKeyArmored, it.fingerprint, it.dropId) }
        return StoredIdentity(
            mine = mine,
            peer = firstPeer,
            userAlias = st.userAlias,
            relayUrl = st.relayServerUrl,
            myAvatarBase64 = st.myAvatarBase64,
            myAvatarHash = st.myAvatarHash,
            contacts = st.contacts,
            groups = st.groups,
            duressPinHash = lockManager.duressPinHash,
            appPinHash = lockManager.appPinHash,
            pinSaltHex = lockManager.pinSaltHex
        )
    }

    private fun persistIdentityAsync() {
        if (_uiState.value.ephemeralMode) return
        val snapshot = buildIdentitySnapshot() ?: return
        viewModelScope.launch { identityRepo.saveIdentity(snapshot, ephemeralMode = false) }
    }

    fun generateFreshKeys(alias: String? = null, exitOnboardingWhenDone: Boolean = false) {
        val resolvedAlias = if (!alias.isNullOrBlank()) GhostKeyUtils.sanitizeAlias(alias) else _uiState.value.userAlias
        _uiState.value = _uiState.value.copy(userAlias = resolvedAlias, isGeneratingKey = true)
        viewModelScope.launch {
            try {
                val pair = identityRepo.generateFreshIdentity(resolvedAlias.ifBlank { null })
                messagingService.clearAllMessages()
                StandalonePgpEngine.clearCache()
                _uiState.value = _uiState.value.copy(
                    myPgpKeys = pair,
                    isGeneratingKey = false,
                    isOnboarding = if (exitOnboardingWhenDone) false else _uiState.value.isOnboarding,
                    activeChatContact = null,
                    activeChatGroup = null
                )
                if (!_uiState.value.ephemeralMode) {
                    buildIdentitySnapshot()?.let { identityRepo.saveIdentity(it, ephemeralMode = false) }
                }
                val st = _uiState.value
                torCoordinator.connectOrAwaitTor(pair, st.groups, st.offlineBleMeshEnabled, st.offlineWifiDirectEnabled)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isGeneratingKey = false,
                    isOnboarding = if (exitOnboardingWhenDone) false else _uiState.value.isOnboarding
                )
                toast("Key generation failed: ${e.message}", Toast.LENGTH_LONG)
            }
        }
    }

    fun setOfflineBleMeshEnabled(enabled: Boolean) {
        val st = _uiState.value
        _uiState.value = st.copy(offlineBleMeshEnabled = enabled)
        identityRepo.saveOfflineMeshPrefs(enabled, st.offlineWifiDirectEnabled)
        if (enabled) st.myPgpKeys?.dropId?.let { transport.bleMesh.start(it) } else transport.bleMesh.stop()
    }

    fun setOfflineWifiDirectEnabled(enabled: Boolean) {
        val st = _uiState.value
        _uiState.value = st.copy(offlineWifiDirectEnabled = enabled)
        identityRepo.saveOfflineMeshPrefs(st.offlineBleMeshEnabled, enabled)
        if (enabled) transport.wifiDirect.startDiscovery() else transport.wifiDirect.stop()
    }

    fun decryptMessage(msg: ChatMessage, dropId: String, fromRelay: Boolean = true) {
        val keys = _uiState.value.myPgpKeys ?: return
        viewModelScope.launch {
            try {
                val st = _uiState.value
                val decrypted = messagingService.decryptAndProcess(
                    msg = msg,
                    dropId = dropId,
                    myKeys = keys,
                    contacts = st.contacts,
                    groups = st.groups,
                    fromRelay = fromRelay,
                    onGroupInviteReceived = { newGroup ->
                        val cur = _uiState.value.groups
                        if (cur.none { it.id == newGroup.id || it.groupDropId == newGroup.groupDropId }) {
                            _uiState.value = _uiState.value.copy(groups = cur + newGroup)
                            torCoordinator.addSubscription(newGroup.groupDropId, newGroup.groupReadToken)
                            persistCurrentStateSync()
                            toast("Invited to group: ${newGroup.name} 👥", Toast.LENGTH_LONG)
                        }
                    },
                    onProfileUpdated = { senderDrop, alias, avatarB64, avatarHash ->
                        val updatedContacts = vault.updateContactProfile(_uiState.value.contacts, senderDrop, alias, avatarB64, avatarHash)
                        if (updatedContacts !== _uiState.value.contacts) {
                            _uiState.value = _uiState.value.copy(contacts = updatedContacts)
                            persistIdentityAsync()
                        }
                    },
                    onGroupControlReceived = { ctrl, senderDrop -> handleIncomingGroupControl(ctrl, senderDrop) },
                    onChatClearedByPeer = { _, peerAlias ->
                        toast("$peerAlias cleared this chat for both sides", Toast.LENGTH_LONG)
                    }
                )
                if (decrypted != null) {
                    val activeId = _uiState.value.activeChatGroup?.id ?: _uiState.value.activeChatContact?.dropId
                    if (isForeground && _uiState.value.navScreen == AppNavScreen.CHAT_VIEW && activeId == decrypted.chatId) {
                        markChatRead(decrypted.chatId)
                    } else if (!isForeground) {
                        PushNotificationManager.postMessageNotification(appContext, decrypted.isAudio, decrypted.isMedia)
                    }
                }
            } catch (_: Exception) {
                if (!isForeground) PushNotificationManager.postMessageNotification(appContext, false, false)
            }
        }
    }

    private fun handleIncomingGroupControl(ctrl: MessageContent.GroupControl, senderDrop: String?) {
        val cur = _uiState.value.groups
        val idx = cur.indexOfFirst { it.id == ctrl.groupId }
        if (idx < 0) return
        val group = cur[idx]
        val updatedGroup = when (ctrl.action) {
            WireMessage.GROUP_ACTION_LEAVE -> vault.removeGroupMember(group, ctrl.targetDropId ?: senderDrop ?: return)
            WireMessage.GROUP_ACTION_KEY_ROTATE -> group.copy(sharedKeyBase64 = ctrl.newSharedKey ?: return)
            else -> return
        }
        val list = cur.toMutableList().apply { set(idx, updatedGroup) }
        _uiState.value = _uiState.value.copy(
            groups = list,
            activeChatGroup = if (_uiState.value.activeChatGroup?.id == updatedGroup.id) updatedGroup else _uiState.value.activeChatGroup
        )
        persistIdentityAsync()
    }

    private fun activeTarget(): ChatTarget? {
        val st = _uiState.value
        return when {
            st.activeChatGroup != null -> ChatTarget.Group(st.activeChatGroup)
            st.activeChatContact != null -> ChatTarget.Direct(st.activeChatContact)
            else -> null
        }
    }

    private fun launchSendToActiveChat(action: suspend (ChatTarget, PgpKeyPairArmored, String) -> Result<DeliveryState>) {
        val target = activeTarget() ?: return toast("Please select a contact or group first!", Toast.LENGTH_LONG)
        val keys = _uiState.value.myPgpKeys ?: return toast("No active PGP key in RAM. Please generate keys first.", Toast.LENGTH_SHORT)
        val alias = _uiState.value.userAlias
        viewModelScope.launch {
            action(target, keys, alias).onFailure { toast(messagingService.formatFriendlySendError(it), Toast.LENGTH_LONG) }
        }
    }

    fun sendMessageToActiveChat(text: String, replyToId: String? = null) =
        launchSendToActiveChat { t, k, a -> messagingService.sendText(t, text, k, a, replyToId) }
    fun sendMediaToActiveChat(uri: Uri) = launchSendToActiveChat { t, k, a -> messagingService.sendMediaUri(t, uri, k, a) }

    fun startRecordingAudio() {
        if (activeTarget() == null) return toast("Please select a contact or group first!", Toast.LENGTH_SHORT)
        if (!audioCoordinator.startRecording()) toast("Could not access microphone", Toast.LENGTH_SHORT)
    }

    fun onStopAudioRecording() {
        val result = audioCoordinator.stopRecording() ?: return
        launchSendToActiveChat { t, k, a -> messagingService.sendAudioNote(t, result, k, a) }
    }

    fun onCancelAudioRecording() = audioCoordinator.cancelRecording()
    fun toggleAudioPlayback(msg: ChatMessage) {
        (msg.content as? MessageContent.AudioNote)?.audioBytes?.let { audioCoordinator.togglePlayback(msg.id, it) }
    }
    fun seekAudio(msg: ChatMessage, fraction: Float) = audioCoordinator.seekTo(msg.id, fraction)

    fun navigateTo(screen: AppNavScreen) {
        val clearActive = screen != AppNavScreen.CHAT_VIEW
        _uiState.value = _uiState.value.copy(
            navScreen = screen,
            activeChatContact = if (clearActive) null else _uiState.value.activeChatContact,
            activeChatGroup = if (clearActive) null else _uiState.value.activeChatGroup
        )
    }

    fun selectChat(chatId: String) {
        val st = _uiState.value
        markChatRead(chatId)
        _uiState.value = st.copy(
            activeChatContact = st.contacts.find { it.dropId == chatId },
            activeChatGroup = st.groups.find { it.id == chatId },
            navScreen = AppNavScreen.CHAT_VIEW
        )
    }

    fun markChatRead(chatId: String) {
        lastReadByChat.update { it + (chatId to System.currentTimeMillis()) }
    }

    fun dismissKeyChangedWarning(dropId: String) {
        val st = _uiState.value
        val updated = st.contacts.map { if (it.dropId == dropId) it.copy(keyChangedWarning = false) else it }
        _uiState.value = st.copy(
            contacts = updated,
            activeChatContact = st.activeChatContact?.takeIf { it.dropId == dropId }?.copy(keyChangedWarning = false)
                ?: st.activeChatContact
        )
        persistIdentityAsync()
    }

    fun togglePinChats(chatIds: Set<String>) = updateChatFlags(chatIds) { curPinned, _, _ ->
        val target = !curPinned
        Triple(target, null, null)
    }

    fun toggleMuteChats(chatIds: Set<String>) = updateChatFlags(chatIds) { _, curMuted, _ ->
        val target = !curMuted
        Triple(null, target, null)
    }

    fun toggleArchiveChats(chatIds: Set<String>) = updateChatFlags(chatIds) { _, _, curArchived ->
        val target = !curArchived
        Triple(null, null, target)
    }

    private inline fun updateChatFlags(
        chatIds: Set<String>,
        compute: (anyPinned: Boolean, anyMuted: Boolean, anyArchived: Boolean) -> Triple<Boolean?, Boolean?, Boolean?>
    ) {
        if (chatIds.isEmpty()) return
        val st = _uiState.value
        val matchingContacts = st.contacts.filter { it.dropId in chatIds }
        val matchingGroups = st.groups.filter { it.id in chatIds }
        val allPinned = (matchingContacts.map { it.isPinned } + matchingGroups.map { it.isPinned }).all { it }
        val allMuted = (matchingContacts.map { it.isMuted } + matchingGroups.map { it.isMuted }).all { it }
        val allArchived = (matchingContacts.map { it.isArchived } + matchingGroups.map { it.isArchived }).all { it }
        val (pin, mute, arch) = compute(allPinned, allMuted, allArchived)
        val updatedContacts = st.contacts.map { c ->
            if (c.dropId in chatIds) c.copy(
                isPinned = pin ?: c.isPinned,
                isMuted = mute ?: c.isMuted,
                isArchived = arch ?: c.isArchived
            ) else c
        }
        val updatedGroups = st.groups.map { g ->
            if (g.id in chatIds) g.copy(
                isPinned = pin ?: g.isPinned,
                isMuted = mute ?: g.isMuted,
                isArchived = arch ?: g.isArchived
            ) else g
        }
        _uiState.value = st.copy(contacts = updatedContacts, groups = updatedGroups)
        persistIdentityAsync()
    }

    fun clearChatsHistory(chatIds: Set<String>) {
        chatIds.forEach { messagingService.clearChatMessages(it) }
    }

    fun deleteChats(chatIds: Set<String>) {
        val st = _uiState.value
        val groupIds = st.groups.filter { it.id in chatIds }.map { it.id }
        val contactIds = st.contacts.filter { it.dropId in chatIds }.map { it.dropId }
        groupIds.forEach { leaveAndDeleteGroup(it) }
        contactIds.forEach { deleteContact(it) }
        // Clear any leftover messages for unsaved chatIds
        chatIds.filter { it !in groupIds && it !in contactIds }.forEach { messagingService.clearChatMessages(it) }
    }

    fun addOrOpenContact(contact: Contact) {
        val st = _uiState.value
        val isNew = st.contacts.none { it.dropId == contact.dropId }
        val updated = vault.upsertContact(st.contacts, contact)
        val resolved = updated.firstOrNull { it.dropId == contact.dropId } ?: contact
        _uiState.value = st.copy(contacts = updated, activeChatContact = resolved, activeChatGroup = null, navScreen = AppNavScreen.CHAT_VIEW)
        persistIdentityAsync()
        if (isNew && (st.myAvatarBase64 != null || st.userAlias.isNotBlank())) {
            st.myPgpKeys?.let { keys ->
                viewModelScope.launch {
                    messagingService.broadcastProfileUpdate(listOf(resolved), keys, st.userAlias, st.myAvatarBase64, st.myAvatarHash)
                }
            }
        }
    }

    fun createGroup(group: GroupChat) {
        val st = _uiState.value
        _uiState.value = st.copy(groups = st.groups + group, activeChatGroup = group, activeChatContact = null, navScreen = AppNavScreen.CHAT_VIEW)
        torCoordinator.addSubscription(group.groupDropId, group.groupReadToken)
        persistIdentityAsync()
        st.myPgpKeys?.let { keys -> viewModelScope.launch { messagingService.inviteMembersToGroup(group, keys, st.userAlias) } }
    }

    fun setContactVerified(contactDropId: String, verified: Boolean) {
        val st = _uiState.value
        val updated = st.contacts.map { if (it.dropId == contactDropId) it.copy(isVerified = verified, keyChangedWarning = false) else it }
        _uiState.value = st.copy(
            contacts = updated,
            activeChatContact = st.activeChatContact?.takeIf { it.dropId == contactDropId }?.let { updated.find { c -> c.dropId == contactDropId } }
                ?: st.activeChatContact
        )
        persistIdentityAsync()
    }

    fun setDisappearingTimerForActiveContact(ttlSeconds: Long?) {
        val contact = _uiState.value.activeChatContact ?: return
        val updatedContact = contact.copy(disappearingTtlSeconds = ttlSeconds)
        val updatedList = _uiState.value.contacts.map { if (it.dropId == contact.dropId) updatedContact else it }
        _uiState.value = _uiState.value.copy(contacts = updatedList, activeChatContact = updatedContact)
        persistIdentityAsync()
    }

    fun setProfilePictureFromUri(uri: Uri) {
        viewModelScope.launch {
            val res = vault.loadSanitizedAvatar(uri) ?: return@launch toast("Could not process profile picture", Toast.LENGTH_SHORT)
            val (b64, hash) = res
            if (hash == _uiState.value.myAvatarHash) return@launch
            _uiState.value = _uiState.value.copy(myAvatarBase64 = b64, myAvatarHash = hash)
            persistIdentityAsync()
            val st = _uiState.value
            st.myPgpKeys?.let { keys -> messagingService.broadcastProfileUpdate(st.contacts, keys, st.userAlias, b64, hash) }
            toast("Profile picture updated (EXIF stripped)", Toast.LENGTH_SHORT)
        }
    }

    fun clearChatHistory(chatId: String) = messagingService.clearChatMessages(chatId)

    fun clearChatForBoth(contact: Contact) {
        val st = _uiState.value
        val keys = st.myPgpKeys
        messagingService.clearChatMessages(contact.dropId)
        if (keys == null) {
            toast("Cleared locally (no PGP key to notify peer)", Toast.LENGTH_SHORT)
            return
        }
        viewModelScope.launch {
            messagingService.sendClearChat(contact, keys, st.userAlias)
                .onSuccess { toast("Chat cleared for both sides", Toast.LENGTH_SHORT) }
                .onFailure { toast("Cleared locally; could not notify peer (${it.message})", Toast.LENGTH_LONG) }
        }
    }

    fun deleteContact(dropId: String) {
        messagingService.clearChatMessages(dropId)
        val st = _uiState.value
        val clearedActive = st.activeChatContact?.dropId == dropId
        _uiState.value = st.copy(
            contacts = st.contacts.filterNot { it.dropId == dropId },
            activeChatContact = if (clearedActive) null else st.activeChatContact,
            navScreen = if (clearedActive) AppNavScreen.CHATS_LIST else st.navScreen
        )
        persistIdentityAsync()
    }

    fun leaveAndDeleteGroup(groupId: String) {
        val st = _uiState.value
        val group = st.groups.find { it.id == groupId } ?: return
        viewModelScope.launch {
            messagingService.sendGroupControl(group, WireMessage.GROUP_ACTION_LEAVE, st.myPgpKeys?.dropId, userAlias = st.userAlias)
        }
        messagingService.clearChatMessages(groupId)
        val clearedActive = st.activeChatGroup?.id == groupId
        _uiState.value = st.copy(
            groups = st.groups.filterNot { it.id == groupId },
            activeChatGroup = if (clearedActive) null else st.activeChatGroup,
            navScreen = if (clearedActive) AppNavScreen.CHATS_LIST else st.navScreen
        )
        persistIdentityAsync()
    }

    fun startOfflineApkSharing(createWifiDirectGroup: Boolean = true) { transport.startOfflineApkSharing(createWifiDirectGroup) }
    fun stopOfflineApkSharing() { transport.stopOfflineApkSharing() }

    fun exportEncryptedBackup(passphrase: String): Result<String> {
        val snapshot = buildIdentitySnapshot() ?: return Result.failure(IllegalStateException("No identity loaded"))
        return vault.exportBackup(snapshot, passphrase)
    }

    fun importEncryptedBackup(backupJson: String, passphrase: String): Result<Unit> =
        vault.importBackup(backupJson, passphrase).map { stored ->
            applyLoadedIdentity(stored)
            if (!_uiState.value.ephemeralMode) identityRepo.saveIdentitySync(stored, ephemeralMode = false)
        }

    fun sendContactIntroduction(targetContact: Contact, contactToIntroduce: Contact) {
        val keys = _uiState.value.myPgpKeys ?: return
        viewModelScope.launch {
            messagingService.sendContactIntroduction(targetContact, contactToIntroduce, keys, _uiState.value.userAlias)
                .onSuccess { toast("Introduction sent to ${targetContact.alias} 🤝", Toast.LENGTH_SHORT) }
                .onFailure { toast("Failed to introduce: ${it.message}", Toast.LENGTH_LONG) }
        }
    }

    fun addIntroducedContact(alias: String, dropId: String, armoredKey: String, safetyCode: String?) {
        val st = _uiState.value
        vault.parseIntroducedContact(st.myPgpKeys, st.contacts, alias, dropId, armoredKey, safetyCode)
            .onSuccess { contact ->
                _uiState.value = st.copy(contacts = st.contacts + contact)
                persistIdentityAsync()
                if (st.myAvatarBase64 != null || st.userAlias.isNotBlank()) {
                    st.myPgpKeys?.let { keys ->
                        viewModelScope.launch {
                            messagingService.broadcastProfileUpdate(listOf(contact), keys, st.userAlias, st.myAvatarBase64, st.myAvatarHash)
                        }
                    }
                }
                toast("Added ${contact.alias} to contacts 🛡️", Toast.LENGTH_SHORT)
            }
            .onFailure { toast(it.message ?: "Could not add contact", Toast.LENGTH_LONG) }
    }

    fun setAppPin(pin: String?) {
        val st = _uiState.value
        runCatching {
            val locked = lockManager.setAppPin(pin, st.ephemeralMode)
            _uiState.value = st.copy(lockEnabled = locked, appPinConfigured = lockManager.hasAppPin)
            persistIdentityAsync()
        }.onFailure { err ->
            toast("Could not save PIN: ${err.message ?: "error"}", Toast.LENGTH_LONG)
        }
    }

    fun setDuressPin(pin: String?) {
        val st = _uiState.value
        runCatching {
            lockManager.setDuressPin(pin, st.lockEnabled, st.ephemeralMode)
            _uiState.value = st.copy(duressPinConfigured = lockManager.hasDuressPin)
            persistIdentityAsync()
        }.onFailure { err ->
            toast("Could not save Duress PIN: ${err.message ?: "error"}", Toast.LENGTH_LONG)
        }
    }

    fun saveAllSettings(newUrl: String, newTor: Boolean, newKeepAlive: Boolean, newEphemeral: Boolean, newLock: Boolean, newAlias: String) {
        val cleanUrl = GhostKeyUtils.sanitizeRelayUrl(newUrl)
        val cleanAlias = GhostKeyUtils.sanitizeAlias(newAlias)
        val effectiveTor = newTor || torCoordinator.requiresTor(false, cleanUrl)

        var effectiveLock = newLock
        if (newLock && !lockManager.hasAppPin && !lockManager.hasDeviceKeyguard()) {
            toast("Set an App PIN below or configure a screen lock in Android settings", Toast.LENGTH_LONG)
            effectiveLock = false
        }

        val current = _uiState.value
        val needsReconnect = torCoordinator.isNetworkConfigChanged(cleanUrl, effectiveTor) ||
            (cleanUrl.isNotBlank() && current.relayStatus != RelayStatus.CONNECTED)

        if (newKeepAlive != current.keepAlive) {
            if (newKeepAlive) TorForegroundService.start(appContext) else TorForegroundService.stop(appContext)
        }

        _uiState.value = current.copy(
            relayServerUrl = cleanUrl,
            useTorSocks = effectiveTor,
            keepAlive = newKeepAlive,
            ephemeralMode = newEphemeral,
            lockEnabled = effectiveLock,
            userAlias = cleanAlias
        )

        identityRepo.saveNetworkConfigPrefs(cleanUrl, cleanAlias, effectiveTor, newKeepAlive, newEphemeral, effectiveLock)
        if (newEphemeral && !current.ephemeralMode) {
            viewModelScope.launch { identityRepo.wipeStorageOnly() }
        } else if (!newEphemeral) {
            persistCurrentStateSync()
        }

        if (needsReconnect) {
            torCoordinator.applyNetworkConfig()
            torCoordinator.ensureTorEngineRunning()
            current.myPgpKeys?.let {
                torCoordinator.connectOrAwaitTor(it, current.groups, current.offlineBleMeshEnabled, current.offlineWifiDirectEnabled)
            }
        }
    }

    fun onAutoSaveSettings(newUrl: String, newAlias: String) {
        val cleanUrl = GhostKeyUtils.sanitizeRelayUrl(newUrl)
        val cleanAlias = GhostKeyUtils.sanitizeAlias(newAlias)
        val current = _uiState.value
        _uiState.value = current.copy(relayServerUrl = cleanUrl, userAlias = cleanAlias)
        if (!current.ephemeralMode) {
            identityRepo.saveRelayAndAliasPrefs(cleanUrl, cleanAlias, ephemeralMode = false, markOnboarded = true)
            persistIdentityAsync()
        }
    }

    fun setShowMyQrDialog(show: Boolean) {
        if (show && _uiState.value.myPgpKeys == null) return toast("Generating PGP identity, please wait...", Toast.LENGTH_SHORT)
        _uiState.value = _uiState.value.copy(showMyQrDialog = show)
    }

    fun setShowPairContactDialog(show: Boolean) { _uiState.value = _uiState.value.copy(showPairContactDialog = show) }

    fun setShowSecurityDiagnostics(show: Boolean) {
        val snap = transport.snapshot.value
        _uiState.value = _uiState.value.copy(
            showSecurityDiagnostics = show,
            lanPeersCount = snap.lanPeerCount,
            blePeersCount = snap.bleMesh.connectedPeers,
            wifiDirectPeersCount = snap.wifiDirect.peers.size
        )
    }

    fun copyToClipboard(label: String, value: String) {
        lockManager.copySensitiveWithAutoClear(label, value, viewModelScope)
        toast("$label copied! (auto-clears in 45s) 📋", Toast.LENGTH_SHORT)
    }

    fun executePanicWipe() {
        messagingService.clearAllMessages()
        transport.wipeAll()
        audioCoordinator.wipe()
        lockManager.clearClipboardAndSecrets()
        TorForegroundService.stop(appContext)
        _uiState.value = MainUiState()
    }

    private fun startDisappearingMessagesReaper() {
        viewModelScope.launch {
            while (true) {
                delay(1_000L)
                messagingService.reapExpiredMessages()
            }
        }
    }

    private fun toast(message: String, duration: Int) {
        Toast.makeText(appContext, message, duration).show()
    }
}
