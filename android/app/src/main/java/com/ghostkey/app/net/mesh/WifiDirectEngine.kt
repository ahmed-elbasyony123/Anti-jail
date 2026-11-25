package com.ghostkey.app.net.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

data class WifiDirectPeer(
    val name: String,
    val address: String,
    val status: Int
)

data class WifiDirectState(
    val isEnabled: Boolean = false,
    val isDiscovering: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerIp: String? = null,
    val groupSsid: String? = null,
    val groupPassphrase: String? = null,
    val wifiQrString: String? = null,
    val error: String? = null,
    val peers: List<WifiDirectPeer> = emptyList(),
    val permissionMissing: Boolean = false
)

/**
 * Briar-hardened Wi-Fi Direct P2P engine:
 * - Re-initializes WifiP2pManager.Channel on BUSY retries up to 5 attempts (Briar HotspotManager pattern)
 * - Polls requestGroupInfo until "DIRECT-" SSID & passphrase populate reliably across OEMs
 * - Generates WPA QR string ("WIFI:T:WPA;S:...;P:...;;") for zero-typing Hotspot / Offline APK joins
 * - Holds WifiLock.WIFI_MODE_FULL_HIGH_PERF while sharing/P2P group is active
 */
class WifiDirectEngine(
    private val context: Context,
    private val onConnectionIpReady: (groupOwnerIp: String, isGroupOwner: Boolean) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "WifiDirectEngine"
        private const val MAX_FRAMEWORK_ATTEMPTS = 5
        private const val MAX_GROUP_INFO_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 1000L
    }

    private val _state = MutableStateFlow(WifiDirectState())
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val manager: WifiP2pManager? by lazy {
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private val wifiManager: WifiManager? by lazy {
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var wifiLock: WifiManager.WifiLock? = null

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val p2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    _state.value = _state.value.copy(
                        isEnabled = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    )
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    val mgr = manager ?: return
                    val ch = channel ?: return
                    if (!MeshPermissions.hasWifiDirectPermissions(appContext)) return
                    runCatching {
                        mgr.requestPeers(ch) { list: WifiP2pDeviceList ->
                            val peers = list.deviceList.map { d ->
                                WifiDirectPeer(
                                    name = d.deviceName.ifBlank { "Nearby Device" },
                                    address = d.deviceAddress,
                                    status = d.status
                                )
                            }
                            _state.value = _state.value.copy(peers = peers)
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val mgr = manager ?: return
                    val ch = channel ?: return
                    @Suppress("DEPRECATION")
                    val netInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (netInfo?.isConnected == true) {
                        mgr.requestConnectionInfo(ch) { info ->
                            val ownerIp = info.groupOwnerAddress?.hostAddress
                            _state.value = _state.value.copy(
                                isGroupOwner = info.isGroupOwner,
                                groupOwnerIp = ownerIp
                            )
                            if (ownerIp != null) {
                                onConnectionIpReady(ownerIp, info.isGroupOwner)
                            }
                        }
                        requestGroupInfoWithRetry(1) {}
                    } else {
                        _state.value = _state.value.copy(
                            isGroupOwner = false,
                            groupOwnerIp = null,
                            groupSsid = null,
                            groupPassphrase = null,
                            wifiQrString = null
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val mgr = manager ?: return
        if (!MeshPermissions.hasWifiDirectPermissions(appContext)) {
            _state.value = _state.value.copy(permissionMissing = true)
            return
        }
        ensureRegistered(mgr)
        val ch = channel ?: return
        _state.value = _state.value.copy(permissionMissing = false, error = null)
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _state.value = _state.value.copy(isDiscovering = true)
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi P2P discovery failed: $reason")
                _state.value = _state.value.copy(isDiscovering = false)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun createSharingGroup(onResult: (Boolean) -> Unit = {}) {
        if (manager == null) {
            _state.value = _state.value.copy(error = "Wi-Fi Direct not supported")
            onResult(false)
            return
        }
        if (!MeshPermissions.hasWifiDirectPermissions(appContext)) {
            _state.value = _state.value.copy(permissionMissing = true)
            onResult(false)
            return
        }
        acquireWifiLock()
        _state.value = _state.value.copy(permissionMissing = false, error = null)
        startGroupAttempt(1, onResult)
    }

    @SuppressLint("MissingPermission")
    private fun startGroupAttempt(attempt: Int, onResult: (Boolean) -> Unit) {
        val mgr = manager ?: return onResult(false)
        // Re-initialize channel on every attempt (Briar HotspotManager workaround for BUSY state)
        closeChannel()
        ensureRegistered(mgr)
        val ch = channel ?: return onResult(false)

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestGroupInfoWithRetry(1) { ok ->
                    if (!ok) releaseWifiLock()
                    onResult(ok)
                }
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && attempt < MAX_FRAMEWORK_ATTEMPTS) {
                    handler.postDelayed({ startGroupAttempt(attempt + 1, onResult) }, RETRY_DELAY_MS)
                } else {
                    _state.value = _state.value.copy(error = "Wi-Fi Direct failed (code $reason)")
                    releaseWifiLock()
                    onResult(false)
                }
            }
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cfg = WifiP2pConfig.Builder()
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .setNetworkName("DIRECT-${randomDigits(2)}-Ghost")
                    .setPassphrase(randomPassphrase())
                    .build()
                mgr.createGroup(ch, cfg, listener)
            } else {
                mgr.createGroup(ch, listener)
            }
        }.onFailure {
            releaseWifiLock()
            onResult(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfoWithRetry(attempt: Int, onComplete: (Boolean) -> Unit) {
        val mgr = manager ?: return onComplete(false)
        val ch = channel ?: return onComplete(false)
        if (!MeshPermissions.hasWifiDirectPermissions(appContext)) return onComplete(false)
        runCatching {
            mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
                val valid = group != null && group.networkName?.startsWith("DIRECT-") == true && !group.passphrase.isNullOrBlank()
                if (valid && group != null) {
                    val ssid = group.networkName
                    val pass = group.passphrase
                    val ownerIp = _state.value.groupOwnerIp ?: "192.168.49.1"
                    _state.value = _state.value.copy(
                        isGroupOwner = true,
                        groupOwnerIp = ownerIp,
                        groupSsid = ssid,
                        groupPassphrase = pass,
                        wifiQrString = "WIFI:T:WPA;S:$ssid;P:$pass;;"
                    )
                    onConnectionIpReady(ownerIp, true)
                    onComplete(true)
                } else if (attempt < MAX_GROUP_INFO_ATTEMPTS) {
                    handler.postDelayed({ requestGroupInfoWithRetry(attempt + 1, onComplete) }, RETRY_DELAY_MS)
                } else {
                    onComplete(false)
                }
            }
        }.onFailure { onComplete(false) }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String, onResult: (Boolean) -> Unit = {}) {
        val mgr = manager ?: return onResult(false)
        val ch = channel ?: return onResult(false)
        if (!MeshPermissions.hasWifiDirectPermissions(appContext)) {
            _state.value = _state.value.copy(permissionMissing = true)
            return onResult(false)
        }
        val config = WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true)
            override fun onFailure(reason: Int) = onResult(false)
        })
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            runCatching { mgr.stopPeerDiscovery(ch, null) }
            runCatching { mgr.removeGroup(ch, null) }
        }
        closeChannel()
        releaseWifiLock()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        _state.value = WifiDirectState()
    }

    private fun ensureRegistered(mgr: WifiP2pManager) {
        if (channel == null) {
            channel = mgr.initialize(appContext, Looper.getMainLooper(), null)
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
    }

    private fun closeChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching { channel?.close() }
        }
        channel = null
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        } else {
            WifiManager.WIFI_MODE_FULL
        }
        runCatching {
            wifiLock = wifiManager?.createWifiLock(lockType, "GhostKey:WifiDirect")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private fun randomDigits(len: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString(len) { repeat(len) { append(chars[random.nextInt(chars.length)]) } }
    }

    private fun randomPassphrase(): String {
        val chars = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(12) { repeat(12) { append(chars[random.nextInt(chars.length)]) } }
    }
}
