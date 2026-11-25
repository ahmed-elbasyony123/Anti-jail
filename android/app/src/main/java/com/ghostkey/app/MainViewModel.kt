package com.ghostkey.app

import android.app.Application
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.ghostkey.app.data.ChatMessage
import com.ghostkey.app.data.ChatSummary
import com.ghostkey.app.data.ChatTarget
import com.ghostkey.app.data.Contact
import com.ghostkey.app.data.GroupChat
import com.ghostkey.app.data.IdentityRepository
import com.ghostkey.app.data.MessagingRepository
import com.ghostkey.app.data.PeerIdentity
import com.ghostkey.app.data.StoredIdentity
import com.ghostkey.app.net.ArtiTorManager
import com.ghostkey.app.net.ArtiTorState
import com.ghostkey.app.net.EncryptedMessageRecord
import com.ghostkey.app.net.LocalLanMesh
import com.ghostkey.app.net.OrbotPortWatcher
import com.ghostkey.app.net.RelayStatus
import com.ghostkey.app.net.TorRelayClient
import com.ghostkey.app.notify.PushNotificationManager
import com.ghostkey.app.service.TorForegroundService
import com.ghostkey.app.util.GhostKeyUtils
import kotlinx.coroutines.Dispatchers
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
    val artiState: ArtiTorState = ArtiTorState.Idle,
    val isRecordingAudio: Boolean = false,
    val recordElapsedMs: Long = 0L,
    val showMyQrDialog: Boolean = false,
    val showPairContactDialog: Boolean = false,
    val showSecurityDiagnostics: Boolean = false,
    val lanPeersCount: Int = 0
)

internal class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val identityRepo = IdentityRepository(appContext)
    private val torClient = TorRelayClient()
    private val messagingRepo = MessagingRepository(appContext.contentResolver, torClient)
    private val audioRecorderManager = AudioRecorderManager(appContext)
    private val audioPlayerManager = AudioPlayerManager()

    private var orbotSocksPort = OrbotPortWatcher.DEFAULT_SOCKS_PORT
    private var orbotWatcher: OrbotPortWatcher? = null
    private var localLanMesh: LocalLanMesh? = null
    private var artiRetryCount = 0
    private var isForeground = false
    private var recordingTimerJob: Job? = null

    private var appPinHash: String? = null
    private var duressPinHash: String? = null
    private var pinSaltHex: String? = null

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val messages: StateFlow<List<ChatMessage>> = messagingRepo.messages
    val playbackState: StateFlow<PlaybackState> = audioPlayerManager.playbackState

    val chatSummaries: StateFlow<List<ChatSummary>> = combine(
        _uiState,
        messagingRepo.messages
    ) { state, msgs ->
        messagingRepo.buildChatSummaries(state.contacts, state.groups, msgs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeChatMessages: StateFlow<List<ChatMessage>> = combine(
        _uiState,
        messagingRepo.messages
    ) { state, msgs ->
        val activeChatId = state.activeChatGroup?.id ?: state.activeChatContact?.dropId ?: ""
        if (activeChatId.isBlank()) emptyList()
        else msgs.filter { it.chatId == activeChatId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        val prefs = identityRepo.readPreferences(OrbotPortWatcher.DEFAULT_SOCKS_PORT)
        orbotSocksPort = prefs.orbotSocksPort
        appPinHash = prefs.appPinHash
        duressPinHash = prefs.duressPinHash
        pinSaltHex = prefs.pinSaltHex

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
            appPinConfigured = !prefs.appPinHash.isNullOrBlank(),
            duressPinConfigured = !prefs.duressPinHash.isNullOrBlank()
        )

        localLanMesh = LocalLanMesh(appContext) { senderDropId, ciphertext ->
            val incoming = messagingRepo.insertLanIncoming(senderDropId, ciphertext)
            if (incoming != null) {
                decryptMessage(incoming, senderDropId, fromRelay = false)
            }
        }

        PushNotificationManager.init(appContext)
        initTorNetworkEngine()
        applyNetworkConfig()

        if (!shouldOnboard && !shouldLock) {
            bootstrapIdentity()
        } else if (shouldLock && identityRepo.hasStoredIdentity()) {
            // Preload salted PIN hashes from Keystore-encrypted storage if prefs were cleared
            viewModelScope.launch {
                runCatching { identityRepo.loadIdentity() }.onSuccess { stored ->
                    if (appPinHash.isNullOrBlank()) appPinHash = stored.appPinHash
                    if (duressPinHash.isNullOrBlank()) duressPinHash = stored.duressPinHash
                    if (pinSaltHex.isNullOrBlank()) pinSaltHex = stored.pinSaltHex
                    _uiState.value = _uiState.value.copy(
                        appPinConfigured = !appPinHash.isNullOrBlank(),
                        duressPinConfigured = !duressPinHash.isNullOrBlank()
                    )
                }
            }
        }

        if (prefs.keepAlive) {
            TorForegroundService.start(appContext)
        }
        observeForegroundServiceState()
        startDisappearingMessagesReaper()
    }

    override fun onCleared() {
        super.onCleared()
        localLanMesh?.stop()
        ArtiTorManager.stop()
        audioRecorderManager.cancelRecording()
        audioPlayerManager.stop()
        orbotWatcher?.stop()
        torClient.disconnectWebSocket()
    }

    fun onForeground() {
        isForeground = true
        MainActivity.isExternalPickerActive = false
        PushNotificationManager.clearMessageNotifications(appContext)
    }

    fun onPauseActivity() {
        isForeground = false
        if (_uiState.value.isRecordingAudio) {
            onCancelAudioRecording()
        }
        val state = _uiState.value
        if (!state.ephemeralMode) {
            identityRepo.saveRelayAndAliasPrefs(state.relayServerUrl, state.userAlias, ephemeralMode = false)
            persistIdentityAsync()
        }
    }

    fun onStopActivity() {
        val state = _uiState.value
        if (!MainActivity.isExternalPickerActive && state.lockEnabled && !state.ephemeralMode && !state.isOnboarding) {
            _uiState.value = state.copy(isLocked = true)
        }
    }

    fun onAppUnlocked() {
        _uiState.value = _uiState.value.copy(isLocked = false)
        if (_uiState.value.myPgpKeys == null) {
            bootstrapIdentity()
        }
    }

    fun onDeviceLockUnavailable(): Boolean {
        return if (appPinHash.isNullOrBlank()) {
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

    fun verifyPin(pin: String): Boolean {
        if (!duressPinHash.isNullOrBlank() && identityRepo.verifyPinConstantTime(pin, duressPinHash, pinSaltHex)) {
            return false // Caller checks triggerDuressIfMatches separately or handles wipe
        }
        if (!appPinHash.isNullOrBlank() && identityRepo.verifyPinConstantTime(pin, appPinHash, pinSaltHex)) {
            return true
        }
        return appPinHash.isNullOrBlank() && duressPinHash.isNullOrBlank()
    }

    fun isDuressPin(pin: String): Boolean =
        !duressPinHash.isNullOrBlank() && identityRepo.verifyPinConstantTime(pin, duressPinHash, pinSaltHex)

    fun completeOnboarding(alias: String, appPin: String?, duressPin: String?, ephemeralMode: Boolean) {
        val cleanAlias = GhostKeyUtils.sanitizeAlias(alias)
        val newSalt = if (!ephemeralMode && (!appPin.isNullOrBlank() || !duressPin.isNullOrBlank())) {
            identityRepo.ensurePinSalt(pinSaltHex)
        } else null

        val newAppHash = if (!appPin.isNullOrBlank()) identityRepo.hashPin(appPin, newSalt) else null
        val newDuressHash = if (!duressPin.isNullOrBlank()) identityRepo.hashPin(duressPin, newSalt) else null
        val lockOn = !ephemeralMode && newAppHash != null

        pinSaltHex = newSalt
        appPinHash = newAppHash
        duressPinHash = newDuressHash

        identityRepo.saveOnboardingCompletion(
            alias = cleanAlias,
            ephemeralMode = ephemeralMode,
            lockEnabled = lockOn,
            appPinHash = newAppHash,
            duressPinHash = newDuressHash,
            pinSaltHex = newSalt
        )

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
                if (running) {
                    sawRunning = true
                } else if (sawRunning && _uiState.value.keepAlive) {
                    // Android 14+ dataSync timeout stopped the service in the background; sync UI state
                    _uiState.value = _uiState.value.copy(keepAlive = false)
                }
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
                viewModelScope.launch { onOrbotPort(port) }
            }.also { it.start() }
        }
    }

    private fun onOrbotPort(port: Int) {
        val changed = orbotSocksPort != port
        orbotSocksPort = port
        identityRepo.saveOrbotPort(port)
        applyNetworkConfig()
        if (changed) {
            _uiState.value.myPgpKeys?.let { connectToRelayMailbox(it) }
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
                val stored = identityRepo.loadIdentity()
                applyLoadedIdentity(stored)
            } catch (e: Exception) {
                identityRepo.wipeStorageOnly()
                toast("The saved identity could not be read, so a new one was created", Toast.LENGTH_LONG)
                generateFreshKeys()
            }
        }
    }

    private fun applyLoadedIdentity(stored: StoredIdentity) {
        val prefs = identityRepo.readPreferences(orbotSocksPort)
        val resolvedAlias = stored.userAlias.ifBlank { prefs.userAlias }
        val resolvedRelay = stored.relayUrl.ifBlank { prefs.relayUrl }
        val loadedContacts = stored.contacts.toMutableList()

        stored.peer?.let { peer ->
            if (loadedContacts.none { it.dropId == peer.dropId }) {
                loadedContacts.add(
                    Contact(
                        id = peer.dropId,
                        alias = "Peer ${peer.fingerprint.take(6)}",
                        dropId = peer.dropId,
                        publicKeyArmored = peer.publicKeyArmored,
                        fingerprint = peer.fingerprint
                    )
                )
            }
        }

        if (!stored.pinSaltHex.isNullOrBlank()) pinSaltHex = stored.pinSaltHex
        if (!stored.appPinHash.isNullOrBlank()) appPinHash = stored.appPinHash
        if (!stored.duressPinHash.isNullOrBlank()) duressPinHash = stored.duressPinHash

        _uiState.value = _uiState.value.copy(
            myPgpKeys = stored.mine,
            userAlias = resolvedAlias,
            relayServerUrl = resolvedRelay,
            contacts = loadedContacts,
            groups = stored.groups,
            appPinConfigured = !appPinHash.isNullOrBlank(),
            duressPinConfigured = !duressPinHash.isNullOrBlank()
        )
        applyNetworkConfig()
        ensureTorEngineRunningIfNeeded()

        val needTor = requiresTor(_uiState.value.useTorSocks, resolvedRelay)
        if (needTor && ArtiTorManager.isAvailable && _uiState.value.artiState !is ArtiTorState.Connected) {
            _uiState.value = _uiState.value.copy(relayStatus = RelayStatus.BUILDING_CIRCUIT)
        } else {
            connectToRelayMailbox(stored.mine)
        }
    }

    private fun buildIdentitySnapshot(): StoredIdentity? {
        val st = _uiState.value
        val mine = st.myPgpKeys ?: return null
        val firstPeer = st.contacts.firstOrNull()?.let {
            PeerIdentity(it.publicKeyArmored, it.fingerprint, it.dropId)
        }
        return StoredIdentity(
            mine = mine,
            peer = firstPeer,
            userAlias = st.userAlias,
            relayUrl = st.relayServerUrl,
            contacts = st.contacts,
            groups = st.groups,
            duressPinHash = duressPinHash,
            appPinHash = appPinHash,
            pinSaltHex = pinSaltHex
        )
    }

    private fun persistIdentityAsync() {
        val st = _uiState.value
        if (st.ephemeralMode) return
        val snapshot = buildIdentitySnapshot() ?: return
        viewModelScope.launch {
            identityRepo.saveIdentity(snapshot, ephemeralMode = false)
        }
    }

    fun generateFreshKeys(alias: String? = null) {
        val resolvedAlias = if (!alias.isNullOrBlank()) GhostKeyUtils.sanitizeAlias(alias) else _uiState.value.userAlias
        _uiState.value = _uiState.value.copy(userAlias = resolvedAlias, isGeneratingKey = true)
        viewModelScope.launch {
            try {
                val pair = identityRepo.generateFreshIdentity(resolvedAlias.ifBlank { null })
                messagingRepo.clearAllMessages()
                StandalonePgpEngine.clearCache()
                _uiState.value = _uiState.value.copy(
                    myPgpKeys = pair,
                    isGeneratingKey = false,
                    activeChatContact = null,
                    activeChatGroup = null
                )
                persistIdentityAsync()
                val st = _uiState.value
                if (requiresTor(st.useTorSocks, st.relayServerUrl) &&
                    ArtiTorManager.isAvailable &&
                    st.artiState !is ArtiTorState.Connected
                ) {
                    _uiState.value = _uiState.value.copy(relayStatus = RelayStatus.BUILDING_CIRCUIT)
                } else {
                    connectToRelayMailbox(pair)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isGeneratingKey = false)
                toast("Key generation failed: ${e.message}", Toast.LENGTH_LONG)
            }
        }
    }

    private fun connectToRelayMailbox(keys: PgpKeyPairArmored) {
        localLanMesh?.start(keys.dropId)
        torClient.subscribeLive(
            dropId = keys.dropId,
            readToken = keys.readToken,
            onMessageReceived = { record ->
                viewModelScope.launch { handleIncomingFromRelay(record) }
            },
            onStatusChanged = { status ->
                _uiState.value = _uiState.value.copy(relayStatus = status)
            }
        )
        for (g in _uiState.value.groups) {
            torClient.addSubscription(g.groupDropId, g.groupReadToken)
        }
    }

    private fun handleIncomingFromRelay(record: EncryptedMessageRecord) {
        val msg = messagingRepo.insertPendingIncoming(record) ?: return
        decryptMessage(msg, record.dropId, fromRelay = true)
    }

    fun decryptMessage(msg: ChatMessage, dropId: String, fromRelay: Boolean = true) {
        val keys = _uiState.value.myPgpKeys ?: return
        viewModelScope.launch {
            try {
                val st = _uiState.value
                val decrypted = messagingRepo.decryptAndProcess(
                    msg = msg,
                    dropId = dropId,
                    myKeys = keys,
                    contacts = st.contacts,
                    groups = st.groups,
                    fromRelay = fromRelay,
                    onGroupInviteReceived = { newGroup ->
                        val currentGroups = _uiState.value.groups
                        if (currentGroups.none { it.id == newGroup.id || it.groupDropId == newGroup.groupDropId }) {
                            _uiState.value = _uiState.value.copy(groups = currentGroups + newGroup)
                            torClient.addSubscription(newGroup.groupDropId, newGroup.groupReadToken)
                            persistIdentityAsync()
                            toast("Invited to group: ${newGroup.name} 👥", Toast.LENGTH_LONG)
                        }
                    }
                )
                if (decrypted != null && !isForeground) {
                    PushNotificationManager.postMessageNotification(
                        context = appContext,
                        isAudio = decrypted.isAudio,
                        hasMedia = decrypted.isMedia
                    )
                }
            } catch (_: Exception) {
                if (!isForeground) {
                    PushNotificationManager.postMessageNotification(
                        context = appContext,
                        isAudio = false,
                        hasMedia = false
                    )
                }
            }
        }
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
        val target = activeTarget()
        val keys = _uiState.value.myPgpKeys
        if (keys == null) {
            toast("No active PGP key in RAM. Please generate keys first.", Toast.LENGTH_SHORT)
            return
        }
        if (target == null) {
            toast("Please select a contact or group first!", Toast.LENGTH_LONG)
            return
        }
        viewModelScope.launch {
            messagingRepo.sendText(target, text, keys, _uiState.value.userAlias, localLanMesh)
                .onFailure { err -> toast(messagingRepo.formatFriendlySendError(err), Toast.LENGTH_LONG) }
        }
    }

    fun sendMediaToActiveChat(uri: Uri) {
        val target = activeTarget()
        val keys = _uiState.value.myPgpKeys
        if (keys == null) {
            toast("No active PGP key in RAM. Please generate keys first.", Toast.LENGTH_SHORT)
            return
        }
        if (target == null) {
            toast("Please select a contact or group first!", Toast.LENGTH_LONG)
            return
        }
        viewModelScope.launch {
            messagingRepo.sendMediaUri(target, uri, keys, _uiState.value.userAlias, localLanMesh)
                .onFailure { err -> toast(messagingRepo.formatFriendlySendError(err), Toast.LENGTH_LONG) }
        }
    }

    fun startRecordingAudio() {
        if (audioRecorderManager.startRecording()) {
            _uiState.value = _uiState.value.copy(isRecordingAudio = true, recordElapsedMs = 0L)
            recordingTimerJob?.cancel()
            recordingTimerJob = viewModelScope.launch {
                val start = SystemClock.elapsedRealtime()
                while (_uiState.value.isRecordingAudio) {
                    _uiState.value = _uiState.value.copy(recordElapsedMs = SystemClock.elapsedRealtime() - start)
                    delay(100)
                }
            }
        } else {
            toast("Failed to start recording", Toast.LENGTH_SHORT)
        }
    }

    fun onStopAudioRecording() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _uiState.value = _uiState.value.copy(isRecordingAudio = false)
        val result = audioRecorderManager.stopRecording() ?: return
        val target = activeTarget() ?: return
        val keys = _uiState.value.myPgpKeys ?: return
        viewModelScope.launch {
            messagingRepo.sendAudioNote(target, result, keys, _uiState.value.userAlias, localLanMesh)
                .onFailure { err -> toast(messagingRepo.formatFriendlySendError(err), Toast.LENGTH_LONG) }
        }
    }

    fun onCancelAudioRecording() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _uiState.value = _uiState.value.copy(isRecordingAudio = false, recordElapsedMs = 0L)
        audioRecorderManager.cancelRecording()
    }

    fun toggleAudioPlayback(msg: ChatMessage) {
        val bytes = msg.audioBytes ?: return
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
        val contact = st.contacts.find { it.dropId == chatId }
        val group = st.groups.find { it.id == chatId }
        _uiState.value = st.copy(
            activeChatContact = contact,
            activeChatGroup = group,
            navScreen = AppNavScreen.CHAT_VIEW
        )
    }

    fun addOrOpenContact(contact: Contact) {
        val st = _uiState.value
        val existingIdx = st.contacts.indexOfFirst { it.dropId == contact.dropId }
        val updated = if (existingIdx >= 0) {
            st.contacts.toMutableList().apply { set(existingIdx, contact) }
        } else {
            st.contacts + contact
        }
        _uiState.value = st.copy(
            contacts = updated,
            activeChatContact = contact,
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
            viewModelScope.launch {
                messagingRepo.inviteMembersToGroup(group, keys, st.userAlias)
            }
        }
    }

    fun setContactVerified(contactDropId: String, verified: Boolean) {
        val st = _uiState.value
        val idx = st.contacts.indexOfFirst { it.dropId == contactDropId }
        if (idx < 0) return
        val updatedContact = st.contacts[idx].copy(isVerified = verified)
        val updatedList = st.contacts.toMutableList().apply { set(idx, updatedContact) }
        _uiState.value = st.copy(
            contacts = updatedList,
            activeChatContact = if (st.activeChatContact?.dropId == contactDropId) updatedContact else st.activeChatContact
        )
        persistIdentityAsync()
    }

    fun setDisappearingTimerForActiveContact(ttlSeconds: Long?) {
        val contact = _uiState.value.activeChatContact ?: return
        val st = _uiState.value
        val idx = st.contacts.indexOfFirst { it.dropId == contact.dropId }
        if (idx < 0) return
        val updatedContact = st.contacts[idx].copy(disappearingTtlSeconds = ttlSeconds)
        val updatedList = st.contacts.toMutableList().apply { set(idx, updatedContact) }
        _uiState.value = st.copy(
            contacts = updatedList,
            activeChatContact = updatedContact
        )
        persistIdentityAsync()
    }

    fun sendContactIntroduction(targetContact: Contact, contactToIntroduce: Contact) {
        val keys = _uiState.value.myPgpKeys ?: return
        viewModelScope.launch {
            messagingRepo.sendContactIntroduction(
                targetContact = targetContact,
                contactToIntroduce = contactToIntroduce,
                myKeys = keys,
                userAlias = _uiState.value.userAlias,
                lanMesh = localLanMesh
            ).onSuccess {
                toast("Introduction sent to ${targetContact.alias} 🤝", Toast.LENGTH_SHORT)
            }.onFailure { e ->
                toast("Failed to introduce: ${e.message}", Toast.LENGTH_LONG)
            }
        }
    }

    fun addIntroducedContact(alias: String, dropId: String, armoredKey: String, safetyCode: String?) {
        val cleanDropId = dropId.trim().lowercase()
        if (!StandalonePgpEngine.isValidHex32(cleanDropId)) {
            toast("Invalid Drop-ID in contact introduction", Toast.LENGTH_SHORT)
            return
        }
        val info = runCatching { StandalonePgpEngine.parsePeerKey(armoredKey) }.getOrNull()
        if (info == null) {
            toast("Invalid PGP key in contact introduction", Toast.LENGTH_SHORT)
            return
        }
        if (info.dropId != null && info.dropId != cleanDropId) {
            toast("Security warning: Introduced Drop-ID does not match key!", Toast.LENGTH_LONG)
            return
        }
        val st = _uiState.value
        if (st.contacts.any { it.dropId == cleanDropId || it.fingerprint == info.fingerprint }) {
            toast("Contact already exists in your address book", Toast.LENGTH_SHORT)
            return
        }
        val cleanAlias = GhostKeyUtils.sanitizeAlias(alias)
        val verifiedSas = st.myPgpKeys?.let { StandalonePgpEngine.generateSafetyCode(it.fingerprint, info.fingerprint) }
        val isVerified = safetyCode != null && verifiedSas != null &&
            safetyCode.replace(" ", "") == verifiedSas.replace(" ", "")
        val newContact = Contact(
            id = cleanDropId,
            alias = cleanAlias.ifBlank { info.alias ?: "Peer" },
            dropId = cleanDropId,
            publicKeyArmored = info.publicKeyArmored,
            fingerprint = info.fingerprint,
            isVerified = isVerified,
            safetyCode = verifiedSas
        )
        _uiState.value = st.copy(contacts = st.contacts + newContact)
        persistIdentityAsync()
        toast("Added ${newContact.alias} to contacts 🛡️", Toast.LENGTH_SHORT)
    }

    fun setAppPin(pin: String?) {
        val st = _uiState.value
        val salt = if (pin != null) identityRepo.ensurePinSalt(pinSaltHex) else pinSaltHex
        val hash = if (pin != null) identityRepo.hashPin(pin, salt) else null
        pinSaltHex = salt
        appPinHash = hash
        val newLockEnabled = if (hash != null) true else false
        identityRepo.savePinsToPrefs(hash, duressPinHash, salt, newLockEnabled, st.ephemeralMode)
        _uiState.value = st.copy(
            lockEnabled = newLockEnabled,
            appPinConfigured = hash != null
        )
        persistIdentityAsync()
    }

    fun setDuressPin(pin: String?) {
        val st = _uiState.value
        val salt = if (pin != null) identityRepo.ensurePinSalt(pinSaltHex) else pinSaltHex
        val hash = if (pin != null) identityRepo.hashPin(pin, salt) else null
        pinSaltHex = salt
        duressPinHash = hash
        identityRepo.savePinsToPrefs(appPinHash, hash, salt, st.lockEnabled, st.ephemeralMode)
        _uiState.value = st.copy(duressPinConfigured = hash != null)
        persistIdentityAsync()
    }

    fun saveAllSettings(
        newUrl: String,
        newTor: Boolean,
        newKeepAlive: Boolean,
        newEphemeral: Boolean,
        newLock: Boolean,
        newAlias: String
    ) {
        val cleanUrl = GhostKeyUtils.sanitizeRelayUrl(newUrl)
        val cleanAlias = GhostKeyUtils.sanitizeAlias(newAlias)
        val effectiveTor = newTor || cleanUrl.contains(".onion", ignoreCase = true)

        var effectiveLock = newLock
        if (newLock && appPinHash.isNullOrBlank()) {
            val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguard.isDeviceSecure) {
                toast("Set an App PIN below or configure a screen lock in Android settings", Toast.LENGTH_LONG)
                effectiveLock = false
            }
        }

        val current = _uiState.value
        val networkChanged = cleanUrl != current.relayServerUrl || effectiveTor != current.useTorSocks
        val keepAliveChanged = newKeepAlive != current.keepAlive
        val ephemeralChanged = newEphemeral != current.ephemeralMode

        if (keepAliveChanged) {
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

        identityRepo.saveNetworkConfigPrefs(
            relayUrl = cleanUrl,
            alias = cleanAlias,
            useTor = effectiveTor,
            keepAlive = newKeepAlive,
            ephemeralMode = newEphemeral,
            lockEnabled = effectiveLock
        )

        if (ephemeralChanged && newEphemeral) {
            viewModelScope.launch { identityRepo.wipeStorageOnly() }
        } else if (!newEphemeral) {
            persistIdentityAsync()
        }

        if (networkChanged) {
            applyNetworkConfig()
            ensureTorEngineRunningIfNeeded()
            current.myPgpKeys?.let { connectToRelayMailbox(it) }
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
        val count = localLanMesh?.activePeersCount ?: 0
        _uiState.value = _uiState.value.copy(showSecurityDiagnostics = show, lanPeersCount = count)
    }

    fun copyToClipboard(label: String, value: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("$label copied! (auto-clears in 45s) 📋", Toast.LENGTH_SHORT)
        viewModelScope.launch {
            delay(45_000L)
            try {
                if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == value) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) {}
        }
    }

    fun executePanicWipe() {
        messagingRepo.clearAllMessages()
        ArtiTorManager.wipe(appContext)
        ArtiTorManager.stop()
        audioPlayerManager.wipe()
        audioRecorderManager.cancelRecording()
        localLanMesh?.stop()
        identityRepo.wipeAllSync()
        try {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {}
        appPinHash = null
        duressPinHash = null
        pinSaltHex = null
        torClient.wipe()
        TorForegroundService.stop(appContext)
        _uiState.value = MainUiState()
    }

    private fun startDisappearingMessagesReaper() {
        viewModelScope.launch {
            while (true) {
                delay(1_000L)
                messagingRepo.reapExpiredMessages()
            }
        }
    }

    private fun toast(message: String, duration: Int) {
        Toast.makeText(appContext, message, duration).show()
    }
}
