package com.ghostkey.app.net

import android.content.Context
import android.util.Log
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.data.GroupChat
import com.ghostkey.app.data.IdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class TorNetworkParams(
    val relayServerUrl: String,
    val useTorSocks: Boolean,
    val keys: PgpKeyPairArmored?,
    val groups: List<GroupChat>,
    val offlineBleMeshEnabled: Boolean,
    val offlineWifiDirectEnabled: Boolean
)

/**
 * Coordinates embedded Arti Tor, Orbot SOCKS5 fallback, offline mesh startup,
 * and live relay mailbox subscriptions cleanly outside [com.ghostkey.app.MainViewModel].
 */
internal class TorConnectionCoordinator(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val transport: GhostTransport,
    private val identityRepo: IdentityRepository,
    initialSocksPort: Int,
    private val getParams: () -> TorNetworkParams,
    private val onArtiState: (ArtiTorState) -> Unit,
    private val onRelayState: (RelayStatus, String?) -> Unit,
    private val onRelayMessage: (EncryptedMessageRecord) -> Unit,
    private val onRelayConnected: () -> Unit
) {
    private var socksPort: Int = initialSocksPort
    private var orbotWatcher: OrbotPortWatcher? = null
    private var artiRetryCount: Int = 0
    private var latestArtiState: ArtiTorState = ArtiTorState.Idle

    fun requiresTor(useTor: Boolean, relayUrl: String): Boolean =
        useTor || relayUrl.contains(".onion", ignoreCase = true)

    fun start() {
        applyNetworkConfig()
        if (ArtiTorManager.isAvailable) {
            scope.launch {
                ArtiTorManager.state.collect { state ->
                    latestArtiState = state
                    onArtiState(state)
                    handleArtiStateChange(state)
                }
            }
            ensureTorEngineRunning()
        } else {
            startOrbotWatcher()
        }
    }

    private suspend fun handleArtiStateChange(state: ArtiTorState) {
        when (state) {
            is ArtiTorState.Starting -> {
                onRelayState(RelayStatus.BUILDING_CIRCUIT, null)
                if (state.socksPort > 0 && socksPort != state.socksPort) {
                    socksPort = state.socksPort
                    applyNetworkConfig()
                }
            }
            is ArtiTorState.Connected -> {
                socksPort = state.socksPort
                applyNetworkConfig()
                val params = getParams()
                params.keys?.let {
                    connectToRelayMailbox(it, params.groups, params.offlineBleMeshEnabled, params.offlineWifiDirectEnabled)
                }
            }
            is ArtiTorState.Error -> {
                Log.w("GhostKey", "Arti Tor error: ${state.message}, retrying or falling back to Orbot")
                onRelayState(RelayStatus.BUILDING_CIRCUIT, state.message)
                delay(3_000)
                val params = getParams()
                if (artiRetryCount < 2 && requiresTor(params.useTorSocks, params.relayServerUrl)) {
                    artiRetryCount++
                    ArtiTorManager.start(appContext, scope)
                } else {
                    startOrbotWatcher()
                }
            }
            is ArtiTorState.Idle -> Unit
        }
    }

    fun applyNetworkConfig() {
        val params = getParams()
        transport.torClient.updateConfig(
            url = params.relayServerUrl,
            useTor = params.useTorSocks,
            socksPort = socksPort
        )
    }

    fun ensureTorEngineRunning() {
        val params = getParams()
        val needed = requiresTor(params.useTorSocks, params.relayServerUrl)
        if (ArtiTorManager.isAvailable) {
            if (needed && latestArtiState is ArtiTorState.Idle) {
                artiRetryCount = 0
                ArtiTorManager.start(appContext, scope)
            } else if (!needed && latestArtiState !is ArtiTorState.Idle) {
                ArtiTorManager.stop()
            }
        } else if (needed) {
            startOrbotWatcher()
        }
    }

    fun isNetworkConfigChanged(cleanUrl: String, effectiveTor: Boolean): Boolean =
        cleanUrl != transport.torClient.serverBaseUrl ||
            effectiveTor != transport.torClient.useTorProxy ||
            socksPort != transport.torClient.torSocksPort

    /**
     * Connects to the relay mailbox immediately, or defers until the embedded Arti circuit finishes
     * bootstrapping while bringing up configured offline transports.
     */
    fun connectOrAwaitTor(
        keys: PgpKeyPairArmored,
        groups: List<GroupChat>,
        enableBle: Boolean,
        enableWifiDirect: Boolean
    ) {
        val params = getParams()
        val needTor = requiresTor(params.useTorSocks, params.relayServerUrl)
        if (needTor && ArtiTorManager.isAvailable && latestArtiState !is ArtiTorState.Connected) {
            onRelayState(RelayStatus.BUILDING_CIRCUIT, null)
            transport.startOfflineMesh(keys.dropId, enableBle = enableBle, enableWifiDirect = enableWifiDirect)
        } else {
            connectToRelayMailbox(keys, groups, enableBle, enableWifiDirect)
        }
    }

    private fun connectToRelayMailbox(
        keys: PgpKeyPairArmored,
        groups: List<GroupChat>,
        enableBle: Boolean,
        enableWifiDirect: Boolean
    ) {
        transport.startOfflineMesh(keys.dropId, enableBle = enableBle, enableWifiDirect = enableWifiDirect)
        transport.torClient.subscribeLive(
            dropId = keys.dropId,
            readToken = keys.readToken,
            onMessageReceived = onRelayMessage,
            onStatusChanged = { status ->
                transport.onRelayStatusChanged(status)
                val detail = if (status == RelayStatus.CONNECTED) null else transport.torClient.lastConnectionError
                onRelayState(status, detail)
                if (status == RelayStatus.CONNECTED) onRelayConnected()
            }
        )
        for (g in groups) {
            transport.torClient.addSubscription(g.groupDropId, g.groupReadToken)
        }
    }

    fun addSubscription(groupDropId: String, groupReadToken: String) {
        transport.torClient.addSubscription(groupDropId, groupReadToken)
    }

    private fun startOrbotWatcher() {
        if (orbotWatcher != null) return
        orbotWatcher = OrbotPortWatcher(appContext) { port ->
            scope.launch {
                val changed = socksPort != port
                socksPort = port
                identityRepo.saveOrbotPort(port)
                applyNetworkConfig()
                if (changed) {
                    val params = getParams()
                    params.keys?.let {
                        connectToRelayMailbox(it, params.groups, params.offlineBleMeshEnabled, params.offlineWifiDirectEnabled)
                    }
                }
            }
        }.also { it.start() }
    }

    fun stop() {
        ArtiTorManager.stop()
        orbotWatcher?.stop()
        orbotWatcher = null
    }
}
