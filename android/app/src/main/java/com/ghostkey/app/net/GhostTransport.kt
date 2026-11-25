package com.ghostkey.app.net

import android.content.Context
import com.ghostkey.app.data.DeliveryState
import com.ghostkey.app.net.mesh.BleMeshEngine
import com.ghostkey.app.net.mesh.BleMeshStatus
import com.ghostkey.app.net.mesh.WifiDirectEngine
import com.ghostkey.app.net.mesh.WifiDirectState
import com.ghostkey.app.net.share.OfflineAppSharer
import com.ghostkey.app.net.share.OfflineShareState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TransportSnapshot(
    val relayStatus: RelayStatus = RelayStatus.DISCONNECTED,
    val relayErrorDetail: String? = null,
    val artiState: ArtiTorState = ArtiTorState.Idle,
    val lanPeerCount: Int = 0,
    val bleMesh: BleMeshStatus = BleMeshStatus(),
    val wifiDirect: WifiDirectState = WifiDirectState(),
    val offlineShare: OfflineShareState = OfflineShareState()
)

/**
 * Unified transport hub coordinating:
 * 1. Tor Relay (Embedded Arti / Orbot + WebSocket/HTTP)
 * 2. Wi-Fi Direct P2P + Local LAN Mesh
 * 3. Bitchat-style BLE Multi-Hop Store-and-Forward Mesh
 * 4. Briar-style Offline APK Sharing
 */
class GhostTransport(
    private val context: Context,
    val torClient: TorRelayClient,
    val artiTorManager: ArtiTorManager,
    private val onLocalMeshEnvelope: (senderDropId: String, ciphertext: String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null

    val localLanMesh = LocalLanMesh(context, onLocalMeshEnvelope)
    val bleMesh = BleMeshEngine(context, onLocalMeshEnvelope)
    val wifiDirect = WifiDirectEngine(context) { ownerIp, _ ->
        localLanMesh.probeDirectHost(ownerIp)
    }
    val offlineSharer = OfflineAppSharer(context)

    private val _snapshot = MutableStateFlow(TransportSnapshot())
    val snapshot: StateFlow<TransportSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            artiTorManager.state.collect { st ->
                _snapshot.value = _snapshot.value.copy(artiState = st)
            }
        }
        scope.launch {
            bleMesh.status.collect { st ->
                _snapshot.value = _snapshot.value.copy(bleMesh = st)
            }
        }
        scope.launch {
            wifiDirect.state.collect { st ->
                _snapshot.value = _snapshot.value.copy(wifiDirect = st)
            }
        }
        scope.launch {
            offlineSharer.state.collect { st ->
                _snapshot.value = _snapshot.value.copy(offlineShare = st)
            }
        }
        monitorJob = scope.launch {
            while (true) {
                delay(3_000L)
                _snapshot.value = _snapshot.value.copy(
                    lanPeerCount = localLanMesh.activePeersCount,
                    relayErrorDetail = torClient.lastConnectionError
                )
            }
        }
    }

    fun onRelayStatusChanged(status: RelayStatus) {
        _snapshot.value = _snapshot.value.copy(
            relayStatus = status,
            relayErrorDetail = torClient.lastConnectionError
        )
    }

    fun startOfflineMesh(myDropId: String, enableBle: Boolean = true, enableWifiDirect: Boolean = false) {
        if (myDropId.isBlank()) return
        localLanMesh.start(myDropId)
        if (enableBle) {
            bleMesh.start(myDropId)
        }
        if (enableWifiDirect) {
            wifiDirect.startDiscovery()
        }
    }

    fun stopOfflineMesh() {
        localLanMesh.stop()
        bleMesh.stop()
        wifiDirect.stop()
    }

    fun startOfflineApkSharing(createWifiDirectGroup: Boolean = false): OfflineShareState {
        if (createWifiDirectGroup) {
            wifiDirect.createSharingGroup {
                offlineSharer.start(wifiDirect.state.value.groupOwnerIp)
            }
        }
        return offlineSharer.start(wifiDirect.state.value.groupOwnerIp)
    }

    fun stopOfflineApkSharing() {
        offlineSharer.stop()
    }

    suspend fun sendEncryptedDirect(recipientDropId: String, ciphertext: String): Result<DeliveryState> {
        if (localLanMesh.sendDirect(recipientDropId, ciphertext)) {
            return Result.success(DeliveryState.DELIVERED_LAN)
        }
        val relayRes = torClient.sendMessage(dropId = recipientDropId, ciphertext = ciphertext)
        if (relayRes.isSuccess) {
            return Result.success(DeliveryState.SENT_RELAY)
        }
        if (bleMesh.broadcastEncrypted(recipientDropId, ciphertext)) {
            return Result.success(DeliveryState.RELAYED_BLE_MESH)
        }
        return Result.failure(relayRes.exceptionOrNull() ?: IllegalStateException("All transports unavailable"))
    }

    suspend fun sendEncryptedGroup(groupDropId: String, ciphertext: String): Result<DeliveryState> {
        val relayRes = torClient.sendMessage(dropId = groupDropId, ciphertext = ciphertext)
        if (relayRes.isSuccess) {
            return Result.success(DeliveryState.SENT_RELAY)
        }
        if (bleMesh.broadcastEncrypted(groupDropId, ciphertext)) {
            return Result.success(DeliveryState.RELAYED_BLE_MESH)
        }
        return Result.failure(relayRes.exceptionOrNull() ?: IllegalStateException("Group transport unavailable"))
    }

    fun wipeAll() {
        offlineSharer.stop()
        localLanMesh.stop()
        bleMesh.wipe()
        wifiDirect.stop()
        torClient.disconnectLive()
        artiTorManager.wipe()
    }

    fun close() {
        monitorJob?.cancel()
        offlineSharer.stop()
        localLanMesh.stop()
        bleMesh.stop()
        wifiDirect.stop()
        scope.cancel()
    }
}
