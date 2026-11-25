package com.ghostkey.app

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostkey.app.audio.AudioPlayerManager
import com.ghostkey.app.audio.AudioRecorderManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppNavScreen { CHATS_LIST, CHAT_VIEW, SETTINGS }

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
    val offlineShareState: OfflineShareState = OfflineShareState()
)

internal class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val identityRepo = IdentityRepository(appContext)
    private val lockManager = SecurityLockManager(appContext, identityRepo)
    private val vault = IdentityAndContactsVault(appContext.contentResolver, identityRepo)
    private val torClient = TorRelayClient()
    private val transport = GhostTransport(appContext, torClient, ArtiTorManager) { senderDropId, ciphertext ->
        val incoming = messagingService.insertMeshIncoming(senderDropId, ciphertext)
        if (incoming != null) decryptMessage(incoming, senderDropId, fromRelay = false)
    }
    private val messagingService = MessagingService(appContext.contentResolver, transport)
    private val audioRecorderManager = AudioRecorderManager(appContext)
    private val audioPlayerManager = AudioPlayerManager()

    private var orbotSocksPort = OrbotPortWatcher.DEFAULT_SOCKS_PORT
    private var orbotWatcher: OrbotPortWatcher? = null
    private var artiRetryCount = 0
    private var isForeground = false
    private var recordingTimerJob: Job? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val messages: StateFlow<List<ChatMessage>> = messagingService.messages
    val playbackState: StateFlow<PlaybackState> = audioPlayerManager.playbackState

    val chatSummaries: StateFlow<List<ChatSummary>> = combine(_uiState, messagingService.messages) { state, msgs ->
        messagingService.buildChatSummaries(state.contacts, state.groups, msgs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeChatMessages: StateFlow<List<ChatMessage>> = combine(_uiState, messagingService.messages) { state, msgs ->
        val activeChatId = state.activeChatGroup?.id ?: state.activeChatContact?.dropId ?: ""
        if (activeChatId.isBlank()) emptyList() else msgs.filter { it.chatId == activeChatId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        val prefs = identityRepo.readPreferences(OrbotPortWatcher.DEFAULT_SOCKS_PORT)
        orbotSocksPort = prefs.orbotSocksPort
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
            offlineWifiDirectEnabled = prefs.offlineWifiDirectEnabled
        )

        PushNotificationManager.init(appContext)
        observeTransport()
        initTorNetworkEngine()
        applyNetworkConfig()

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

        if (prefs.keepAlive) TorForegroundService.start(appContext)
        observeForegroundServiceState()
        startDisappearingMessagesReaper()
    }

    override fun onCleared() {
        super.onCleared()
        transport.close()
        ArtiTorManager.stop()
        audioRecorderManager.cancelRecording()
        audioPlayerManager.stop()
        orbotWatcher?.stop()
    }

    private fun observeTransport() {
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

    fun completeOnboarding(alias: String, appPin: String?, duressPin: String?, ephemeralMode: Boolean) {
        val cleanAlias = GhostKeyUtils.sanitizeAlias(alias)
        val (newAppHash, newDuressHash, newSalt) = lockManager.configureOnboardingPins(appPin, duressPin, ephemeralMode)
        val lockOn = !ephemeralMode && newAppHash != null

        identityRepo.saveOnboardingCompletion(cleanAlias, ephemeralMode, lockOn, newAppHash, newDuressHash, newSalt)
        _uiState.value = _uiState.value.copy(
            userAlias = cleanAlias,
            ephemeralMode = ephemeralMode,
            lockEnabled = lockOn,
            appPinConfigured = !newAppHash.isNullOrBlank(),
            duressPinConfigured = !newDuressHash.isNullOrBlank(),
            isOnboarding = false
        )
        generateFreshKeys(cleanAlias)
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

    private fun initTorNetworkEngine() {
        if (ArtiTorManager.isAvailable) {
            viewModelScope.launch {
                ArtiTorManager.state.collect { state ->
                    _uiState.value = _uiState.value.copy(artiState = state)
                    when (state) {
                        is ArtiTorState.Starting -> {
                            _uiState.value = _uiState.value.copy(relayStatus = RelayStatus.BUILDING_CIRCUIT)
                            if (state.socksPort > 0 && orbotSocksPort != state.socksPort) {
                                orbotSocksPort = state.socksPort
                                applyNetworkConfig()
                            }
                        }
                        is ArtiTorState.Connected -> {
                            orbotSocksPort = state.socksPort
                            applyNetworkConfig()
                            _uiState.value.myPgpKeys?.let { connectToRelayMailbox(it) }
                        }
                        is ArtiTorState.Error -> {
                            Log.w("GhostKey", "Arti Tor error: ${state.message}, retrying or falling back to Orbot")
                            _uiState.value = _uiState.value.copy(relayStatus = RelayStatus.BUILDING_CIRCUIT)
                            delay(3000)
                            val st = _uiState.value
                            if (artiRetryCount < 2 && requiresTor(st.useTorSocks, st.relayServerUrl)) {
                                artiRetryCount++
                                ArtiTorManager.start(appContext, viewModelScope)
                            } else {
                                startOrbotWatcher()
                            }
                        }
                        is ArtiTorState.Idle -> {}
                    }
                }
            }
            ensureTorEngineRunningIfNeeded()
        } else {
            startOrbotWatcher()
        }
    }

    private fun requiresTor(useTor: Boolean, relayUrl: String): Boolean =
        useTor || relayUrl.contains(".onion", ignoreCase = true)

    private fun ensureTorEngineRunningIfNeeded() {
        val st = _uiState.value
        val needed = requiresTor(st.useTorSocks, st.relayServerUrl)
        if (ArtiTorManager.isAvailable) {
            if (needed && st.artiState is ArtiTorState.Idle) {
                artiRetryCount = 0
                ArtiTorManager.start(appContext, viewModelScope)
            } else if (!needed && st.artiState !is ArtiTorState.Idle) {
                ArtiTorManager.stop()
            }
        } else if (needed) {
            startOrbotWatcher()
        }
    }

    private fun startOrbotWatcher() {
        if (orbotWatcher == null) {
            orbotWatcher = OrbotPortWatcher(appContext) { port ->
                viewModelScope.launch {
                    val changed = orbotSocksPort != port
                    orbotSocksPort = port
                    identityRepo.saveOrbotPort(port)
                    applyNetworkConfig()
                    if (changed) _uiState.value.myPgpKeys?.let { connectToRelayMailbox(it) }
                }
            }.also { it.start() }
        }
    }

    private fun applyNetworkConfig() {
        val st = _uiState.value
        torClient.updateConfig(st.relayServerUrl, st.useTorSocks, socksPort = orbotSocksPort)
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
        val prefs = identityRepo.readPreferences(orbotSocksPort)
        val resolvedAlias = prefs.userAlias.ifBlank { stored.userAlias }
        val resolvedRelay = prefs.relayUrl.ifBlank { stored.relayUrl }
        val loadedContacts = stored.contacts.toMutableList()
        stored.peer?.let { peer ->
            if (loadedContacts.none { it.dropId == peer.dropId }) {
                loadedContacts.add(
                    Contact(peer.dropId, "Peer ${peer.fingerprint.take(6)}", peer.dropId, peer.publicKeyArmored, peer.fingerprint)
                )
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
        applyNetworkConfig()
        ensureTorEngineRunningIfNeeded()

        val needTor = requiresTor(_uiState.value.useTorSocks, resolvedRelay)
        if (needTor && ArtiTorManager.isAvailable && _uiState.value.artiState !is ArtiTorState.Connected) {
            _uiState.value = _uiState.value.copy(relayStatus = RelayStatus.BUILDING_CIRCUIT)
            startConfiguredOfflineMesh(stored.mine.dropId)
        } else {
            connectToRelayMailbox(stored.mine)
        }
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
        val st = _uiState.value
        if (st.ephemeralMode) return
        val snapshot = buildIdentitySnapshot() ?: return
        viewModelScope.launch { identityRepo.saveIdentity(snapshot, ephemeralMode = false) }
    }

    fun generateFreshKeys(alias: String? = null) {
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
                    activeChatContact = null,
                    activeChatGroup = null
                )
                if (!_uiState.value.ephemeralMode) {
                    buildIdentitySnapshot()?.let { identityRepo.saveIdentity(it, ephemeralMode = false) }
                }
                val st = _uiState.value
                if (requiresTor(st.useTorSocks, st.relayServerUrl) &&
                    ArtiTorManager.isAvailable &&
                    st.artiState !is ArtiTorState.Connected
                ) {
                    _uiState.value = _uiState.value.copy(relayStatus = RelayStatus.BUILDING_CIRCUIT)
                    startConfiguredOfflineMesh(pair.dropId)
                } else {
                    connectToRelayMailbox(pair)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isGeneratingKey = false)
                toast("Key generation failed: ${e.message}", Toast.LENGTH_LONG)
            }
        }
    }

    private fun startConfiguredOfflineMesh(dropId: String) {
        val st = _uiState.value
        transport.startOfflineMesh(
            myDropId = dropId,
            enableBle = st.offlineBleMeshEnabled,
            enableWifiDirect = st.offlineWifiDirectEnabled
        )
    }

    fun setOfflineBleMeshEnabled(enabled: Boolean) {
        val st = _uiState.value
        _uiState.value = st.copy(offlineBleMeshEnabled = enabled)
        identityRepo.saveOfflineMeshPrefs(enabled, st.offlineWifiDirectEnabled)
        if (enabled) {
            st.myPgpKeys?.dropId?.let { transport.bleMesh.start(it) }
        } else {
            transport.bleMesh.stop()
        }
    }

    fun setOfflineWifiDirectEnabled(enabled: Boolean) {
        val st = _uiState.value
        _uiState.value = st.copy(offlineWifiDirectEnabled = enabled)
        identityRepo.saveOfflineMeshPrefs(st.offlineBleMeshEnabled, enabled)
        if (enabled) {
            transport.wifiDirect.startDiscovery()
        } else {
            transport.wifiDirect.stop()
        }
    }

    private fun connectToRelayMailbox(keys: PgpKeyPairArmored) {
        startConfiguredOfflineMesh(keys.dropId)
        torClient.subscribeLive(
            dropId = keys.dropId,
            readToken = keys.readToken,
            onMessageReceived = { record ->
                viewModelScope.launch {
                    val msg = messagingService.insertPendingIncoming(record) ?: return@launch
                    decryptMessage(msg, record.dropId, fromRelay = true)
                }
            },
            onStatusChanged = { status ->
                transport.onRelayStatusChanged(status)
                _uiState.value = _uiState.value.copy(
                    relayStatus = status,
                    relayErrorDetail = if (status == RelayStatus.CONNECTED) null else torClient.lastConnectionError
                )
                if (status == RelayStatus.CONNECTED) {
                    viewModelScope.launch { messagingService.flushOutbox() }
                }
            }
        )
        for (g in _uiState.value.groups) {
            torClient.addSubscription(g.groupDropId, g.groupReadToken)
        }
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
                            torClient.addSubscription(newGroup.groupDropId, newGroup.groupReadToken)
                            persistCurrentStateSync()
                            toast("Invited to group: ${newGroup.name} 👥", Toast.LENGTH_LONG)
                        }
                    },
                    onProfileUpdated = { senderDrop, alias, avatarB64, avatarHash ->
                        val updatedContacts = vault.updateContactProfile(_uiState.value.contacts, senderDrop, alias, avatarB64, avatarHash)
                        _uiState.value = _uiState.value.copy(contacts = updatedContacts)
                        persistIdentityAsync()
                    },
                    onGroupControlReceived = { ctrl, senderDrop ->
                        handleIncomingGroupControl(ctrl, senderDrop)
                    }
                )
                if (decrypted != null && !isForeground) {
                    PushNotificationManager.postMessageNotification(appContext, decrypted.isAudio, decrypted.isMedia)
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
            WireMessage.GROUP_ACTION_LEAVE -> {
                val target = ctrl.targetDropId ?: senderDrop ?: return
                vault.removeGroupMember(group, target)
            }
            WireMessage.GROUP_ACTION_KEY_ROTATE -> {
                val newKey = ctrl.newSharedKey ?: return
                group.copy(sharedKeyBase64 = newKey)
            }
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

    fun sendMessageToActiveChat(text: String) {
        val target = activeTarget() ?: return toast("Please select a contact or group first!", Toast.LENGTH_LONG)
        val keys = _uiState.value.myPgpKeys ?: return toast("No active PGP key in RAM. Please generate keys first.", Toast.LENGTH_SHORT)
        viewModelScope.launch {
            messagingService.sendText(target, text, keys, _uiState.value.userAlias)
                .onFailure { toast(messagingService.formatFriendlySendError(it), Toast.LENGTH_LONG) }
        }
    }

    fun sendMediaToActiveChat(uri: Uri) {
        val target = activeTarget() ?: return toast("Please select a contact or group first!", Toast.LENGTH_LONG)
        val keys = _uiState.value.myPgpKeys ?: return
        viewModelScope.launch {
            messagingService.sendMediaUri(target, uri, keys, _uiState.value.userAlias)
                .onFailure { toast(messagingService.formatFriendlySendError(it), Toast.LENGTH_LONG) }
        }
    }

    fun startRecordingAudio() {
        if (activeTarget() == null) return toast("Please select a contact or group first!", Toast.LENGTH_SHORT)
        if (_uiState.value.isRecordingAudio) return
        if (!audioRecorderManager.startRecording()) return toast("Could not access microphone", Toast.LENGTH_SHORT)
        _uiState.value = _uiState.value.copy(isRecordingAudio = true, recordElapsedMs = 0L)
        recordingTimerJob?.cancel()
        val startElapsed = SystemClock.elapsedRealtime()
        recordingTimerJob = viewModelScope.launch {
            while (_uiState.value.isRecordingAudio) {
                delay(100)
                _uiState.value = _uiState.value.copy(recordElapsedMs = SystemClock.elapsedRealtime() - startElapsed)
            }
        }
    }

    fun onStopAudioRecording() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _uiState.value = _uiState.value.copy(isRecordingAudio = false, recordElapsedMs = 0L)
        val result = audioRecorderManager.stopRecording() ?: return
        val target = activeTarget() ?: return
        val keys = _uiState.value.myPgpKeys ?: return
        viewModelScope.launch {
            messagingService.sendAudioNote(target, result, keys, _uiState.value.userAlias)
                .onFailure { toast(messagingService.formatFriendlySendError(it), Toast.LENGTH_LONG) }
        }
    }

    fun onCancelAudioRecording() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _uiState.value = _uiState.value.copy(isRecordingAudio = false, recordElapsedMs = 0L)
        audioRecorderManager.cancelRecording()
    }

    fun toggleAudioPlayback(msg: ChatMessage) {
        val bytes = (msg.content as? MessageContent.AudioNote)?.audioBytes ?: return
        audioPlayerManager.play(msg.id, bytes)
    }

    fun seekAudio(msg: ChatMessage, fraction: Float) {
        audioPlayerManager.seekTo(msg.id, fraction)
    }

    fun navigateTo(screen: AppNavScreen) {
        _uiState.value = _uiState.value.copy(navScreen = screen)
    }

    fun selectChat(chatId: String) {
        val st = _uiState.value
        _uiState.value = st.copy(
            activeChatContact = st.contacts.find { it.dropId == chatId },
            activeChatGroup = st.groups.find { it.id == chatId },
            navScreen = AppNavScreen.CHAT_VIEW
        )
    }

    fun addOrOpenContact(contact: Contact) {
        val st = _uiState.value
        val updated = vault.upsertContact(st.contacts, contact)
        val resolved = updated.firstOrNull { it.dropId == contact.dropId } ?: contact
        _uiState.value = st.copy(
            contacts = updated,
            activeChatContact = resolved,
            activeChatGroup = null,
            navScreen = AppNavScreen.CHAT_VIEW
        )
        persistIdentityAsync()
    }

    fun createGroup(group: GroupChat) {
        val st = _uiState.value
        _uiState.value = st.copy(
            groups = st.groups + group,
            activeChatGroup = group,
            activeChatContact = null,
            navScreen = AppNavScreen.CHAT_VIEW
        )
        torClient.addSubscription(group.groupDropId, group.groupReadToken)
        persistIdentityAsync()
        st.myPgpKeys?.let { keys ->
            viewModelScope.launch { messagingService.inviteMembersToGroup(group, keys, st.userAlias) }
        }
    }

    fun setContactVerified(contactDropId: String, verified: Boolean) {
        val st = _uiState.value
        val updatedList = st.contacts.map {
            if (it.dropId == contactDropId) it.copy(isVerified = verified, keyChangedWarning = false) else it
        }
        _uiState.value = st.copy(
            contacts = updatedList,
            activeChatContact = if (st.activeChatContact?.dropId == contactDropId) {
                updatedList.find { it.dropId == contactDropId }
            } else st.activeChatContact
        )
        persistIdentityAsync()
    }

    fun setDisappearingTimerForActiveContact(ttlSeconds: Long?) {
        val contact = _uiState.value.activeChatContact ?: return
        val st = _uiState.value
        val updatedContact = contact.copy(disappearingTtlSeconds = ttlSeconds)
        val updatedList = st.contacts.map { if (it.dropId == contact.dropId) updatedContact else it }
        _uiState.value = st.copy(contacts = updatedList, activeChatContact = updatedContact)
        persistIdentityAsync()
    }

    fun setProfilePictureFromUri(uri: Uri) {
        viewModelScope.launch {
            val res = vault.loadSanitizedAvatar(uri)
            if (res == null) {
                toast("Could not process profile picture", Toast.LENGTH_SHORT)
                return@launch
            }
            val (b64, hash) = res
            _uiState.value = _uiState.value.copy(myAvatarBase64 = b64, myAvatarHash = hash)
            persistIdentityAsync()
            val st = _uiState.value
            st.myPgpKeys?.let { keys ->
                messagingService.broadcastProfileUpdate(st.contacts, keys, st.userAlias, b64, hash)
            }
            toast("Profile picture updated (EXIF stripped)", Toast.LENGTH_SHORT)
        }
    }

    fun clearChatHistory(chatId: String) {
        messagingService.clearChatMessages(chatId)
    }

    fun deleteContact(dropId: String) {
        messagingService.clearChatMessages(dropId)
        val st = _uiState.value
        _uiState.value = st.copy(
            contacts = st.contacts.filterNot { it.dropId == dropId },
            activeChatContact = if (st.activeChatContact?.dropId == dropId) null else st.activeChatContact,
            navScreen = if (st.activeChatContact?.dropId == dropId) AppNavScreen.CHATS_LIST else st.navScreen
        )
        persistIdentityAsync()
    }

    fun leaveAndDeleteGroup(groupId: String) {
        val st = _uiState.value
        val group = st.groups.find { it.id == groupId } ?: return
        val myDrop = st.myPgpKeys?.dropId
        viewModelScope.launch {
            messagingService.sendGroupControl(group, WireMessage.GROUP_ACTION_LEAVE, myDrop, userAlias = st.userAlias)
        }
        messagingService.clearChatMessages(groupId)
        _uiState.value = st.copy(
            groups = st.groups.filterNot { it.id == groupId },
            activeChatGroup = if (st.activeChatGroup?.id == groupId) null else st.activeChatGroup,
            navScreen = if (st.activeChatGroup?.id == groupId) AppNavScreen.CHATS_LIST else st.navScreen
        )
        persistIdentityAsync()
    }

    fun startOfflineApkSharing(createWifiDirectGroup: Boolean = true) {
        transport.startOfflineApkSharing(createWifiDirectGroup)
    }

    fun stopOfflineApkSharing() {
        transport.stopOfflineApkSharing()
    }

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
                toast("Added ${contact.alias} to contacts 🛡️", Toast.LENGTH_SHORT)
            }
            .onFailure { toast(it.message ?: "Could not add contact", Toast.LENGTH_LONG) }
    }

    fun setAppPin(pin: String?) {
        val st = _uiState.value
        val newLockEnabled = lockManager.setAppPin(pin, st.ephemeralMode)
        _uiState.value = st.copy(lockEnabled = newLockEnabled, appPinConfigured = lockManager.hasAppPin)
        persistIdentityAsync()
    }

    fun setDuressPin(pin: String?) {
        val st = _uiState.value
        lockManager.setDuressPin(pin, st.lockEnabled, st.ephemeralMode)
        _uiState.value = st.copy(duressPinConfigured = lockManager.hasDuressPin)
        persistIdentityAsync()
    }

    fun saveAllSettings(newUrl: String, newTor: Boolean, newKeepAlive: Boolean, newEphemeral: Boolean, newLock: Boolean, newAlias: String) {
        val cleanUrl = GhostKeyUtils.sanitizeRelayUrl(newUrl)
        val cleanAlias = GhostKeyUtils.sanitizeAlias(newAlias)
        val effectiveTor = newTor || cleanUrl.contains(".onion", ignoreCase = true)

        var effectiveLock = newLock
        if (newLock && !lockManager.hasAppPin && !lockManager.hasDeviceKeyguard()) {
            toast("Set an App PIN below or configure a screen lock in Android settings", Toast.LENGTH_LONG)
            effectiveLock = false
        }

        val current = _uiState.value
        val networkChanged = cleanUrl != torClient.serverBaseUrl || effectiveTor != torClient.useTorProxy || orbotSocksPort != torClient.torSocksPort
        val needsReconnect = networkChanged || (cleanUrl.isNotBlank() && current.relayStatus != RelayStatus.CONNECTED)

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
            applyNetworkConfig()
            ensureTorEngineRunningIfNeeded()
            if (requiresTor(effectiveTor, cleanUrl) && ArtiTorManager.isAvailable && _uiState.value.artiState !is ArtiTorState.Connected) {
                _uiState.value = _uiState.value.copy(relayStatus = RelayStatus.BUILDING_CIRCUIT)
            } else {
                current.myPgpKeys?.let { connectToRelayMailbox(it) }
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
        if (show && _uiState.value.myPgpKeys == null) {
            toast("Generating PGP identity, please wait...", Toast.LENGTH_SHORT)
            return
        }
        _uiState.value = _uiState.value.copy(showMyQrDialog = show)
    }

    fun setShowPairContactDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showPairContactDialog = show)
    }

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
        audioPlayerManager.wipe()
        audioRecorderManager.cancelRecording()
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
