package com.ghostkey.app.net.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.ghostkey.app.util.GhostKeyUtils
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class BleMeshStatus(
    val isRunning: Boolean = false,
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val connectedPeers: Int = 0,
    val relayedCount: Int = 0,
    val permissionMissing: Boolean = false
)

/**
 * Bitchat-hardened Bluetooth Low Energy Multi-Hop Mesh Engine:
 * - Self-healing scanner with 5s rate-limit, scan error backoff (1/2/3/5/6), and 30s/120s watchdog
 * - Self-healing advertiser with staggered startup and failure backoff
 * - Stale GATT linkID tracking and ScanResponse originTag deduplication across MAC rotations
 * - 517-byte ATT MTU negotiation + serialized write lock + randomized relay jitter
 */
class BleMeshEngine(
    private val context: Context,
    private val onEncryptedEnvelopeReceived: (senderDropIdHint: String, ciphertext: String) -> Unit
) {
    companion object {
        private const val TAG = "BleMeshEngine"
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000F7B1-0000-1000-8000-00805F9B34FB")
        val MESH_CHAR_UUID: UUID = UUID.fromString("0000F7B2-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val MAX_CLIENT_CONNECTIONS = 4
        private const val SCAN_RATE_LIMIT_MS = 5_000L
        private const val SCAN_RETRY_BASE_MS = 3_000L
        private const val SCAN_MAX_RETRY_DELAY_MS = 30_000L
        private const val SCAN_WATCHDOG_INTERVAL_MS = 30_000L
        private const val SCAN_STALE_RESULT_MS = 120_000L
        private const val ADVERTISE_RETRY_BASE_MS = 3_000L
        private const val ADVERTISE_MAX_RETRY_DELAY_MS = 30_000L
        private const val SCAN_BURST_MS = 12_000L
        private const val SCAN_IDLE_MS = 6_000L
    }

    private val _status = MutableStateFlow(BleMeshStatus())
    val status: StateFlow<BleMeshStatus> = _status.asStateFlow()

    private val router = MeshRouter()
    private val fragmenter = BleFragmenter()
    private val writeMutex = Mutex()

    private val serverCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val serverLinkIDs = ConcurrentHashMap<String, String>()
    private val clientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientCharacteristics = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val discoveredOriginTags = ConcurrentHashMap<String, String>() // originTagHex -> MAC

    @Volatile private var myDropId: String = ""
    @Volatile private var isActive = false
    @Volatile private var scanningDesired = false
    @Volatile private var isCurrentlyScanning = false
    @Volatile private var lastScanStartTime = 0L
    @Volatile private var lastScanResultTime = 0L
    private var scanRetryCount = 0
    private var advertiseRetryCount = 0

    private var scope: CoroutineScope? = null
    private var scanLoopJob: Job? = null
    private var watchdogJob: Job? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverCharacteristic: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertiseRetryCount = 0
            _status.value = _status.value.copy(isAdvertising = true)
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE advertising failed: code=$errorCode")
            _status.value = _status.value.copy(isAdvertising = false)
            if (errorCode == ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ||
                errorCode == ADVERTISE_FAILED_INTERNAL_ERROR ||
                errorCode !in listOf(
                    ADVERTISE_FAILED_ALREADY_STARTED,
                    ADVERTISE_FAILED_DATA_TOO_LARGE,
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED
                )
            ) {
                scheduleAdvertiseRestart()
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (r in results) handleScanResult(r)
        }

        @SuppressLint("MissingPermission")
        override fun onScanFailed(errorCode: Int) {
            isCurrentlyScanning = false
            _status.value = _status.value.copy(isScanning = false)
            stopScanInternal(force = true)
            val baseDelay = when (errorCode) {
                5 -> SCAN_RETRY_BASE_MS * 3 // out of resources
                6 -> 10_000L                // scanning too frequently
                4 -> return                 // feature unsupported (permanent)
                else -> SCAN_RETRY_BASE_MS
            }
            scheduleScanRestart(baseDelay)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(myDropId: String) {
        this.myDropId = myDropId
        if (!MeshPermissions.hasBlePermissions(context)) {
            _status.value = BleMeshStatus(isRunning = false, permissionMissing = true)
            return
        }
        val btMgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = btMgr.adapter
        if (adapter == null || !adapter.isEnabled) {
            _status.value = BleMeshStatus(isRunning = false, permissionMissing = false)
            return
        }

        if (isActive && scope != null) return
        val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = meshScope
        isActive = true
        scanningDesired = true
        lastScanResultTime = System.currentTimeMillis()
        _status.value = BleMeshStatus(isRunning = true, permissionMissing = false)

        meshScope.launch {
            startGattServer(btMgr)
            delay(300) // Bitchat stagger before advertising
            if (isActive) startAdvertising(adapter)
        }
        startDutyCycledScan(adapter, meshScope)
        startScanWatchdog(adapter, meshScope)
    }

    fun updateIdentity(myDropId: String) {
        this.myDropId = myDropId
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        isActive = false
        scanningDesired = false
        scanLoopJob?.cancel()
        scanLoopJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        val activeScope = scope
        scope = null
        activeScope?.cancel()

        if (MeshPermissions.hasBlePermissions(context)) {
            stopScanInternal(force = true)
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            for (gatt in clientGatts.values) {
                runCatching {
                    gatt.disconnect()
                    gatt.close()
                }
            }
            for (device in serverCentrals.values) {
                runCatching { gattServer?.cancelConnection(device) }
            }
            runCatching { gattServer?.close() }
        }
        clientGatts.clear()
        clientCharacteristics.clear()
        serverCentrals.clear()
        serverLinkIDs.clear()
        discoveredOriginTags.clear()
        gattServer = null
        serverCharacteristic = null
        advertiser = null
        scanner = null
        isCurrentlyScanning = false
        _status.value = BleMeshStatus()
    }

    fun wipe() {
        stop()
        router.clear()
    }

    fun broadcastEncrypted(recipientDropId: String?, ciphertext: String): Boolean {
        if (!isActive || !_status.value.isRunning) return false
        val envelopeBytes = JSONObject().apply {
            put("senderDropId", myDropId)
            put("ciphertext", ciphertext)
        }.toString().toByteArray(Charsets.UTF_8)

        // Guard against oversized media envelopes that exceed the ~46 KB BLE fragmentation limit
        if (envelopeBytes.size + 256 > fragmenter.maxPacketBytes) return false

        val packet = router.createOutgoingPacket(
            recipientDropId = recipientDropId,
            payloadBytes = envelopeBytes
        )
        if (!fragmenter.canFragment(packet.encode().size)) return false
        val meshScope = scope ?: return false
        meshScope.launch { sendPacketToAllConnected(packet) }
        return serverCentrals.isNotEmpty() || clientGatts.isNotEmpty()
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        if (!isActive || !MeshPermissions.hasBlePermissions(context)) return
        lastScanResultTime = System.currentTimeMillis()
        scanRetryCount = 0
        val device = result.device ?: return
        val addr = device.address ?: return
        val serviceData = result.scanRecord?.getServiceData(ParcelUuid(MESH_SERVICE_UUID))
        if (serviceData != null && serviceData.size >= 8) {
            val tagHex = GhostKeyUtils.bytesToHex(serviceData.copyOfRange(0, 8))
            val prevAddr = discoveredOriginTags.put(tagHex, addr)
            if (prevAddr != null && prevAddr != addr && clientGatts.containsKey(prevAddr)) {
                return // Same node rotated MAC and is already connected
            }
        }
        if (clientGatts.containsKey(addr) || clientGatts.size >= MAX_CLIENT_CONNECTIONS) return
        connectAsClient(device)
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer(btMgr: BluetoothManager) {
        if (!MeshPermissions.hasBlePermissions(context) || !isActive) return
        val server = btMgr.openGattServer(context, object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (!isActive) return
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val linkId = UUID.randomUUID().toString()
                    serverLinkIDs[device.address] = linkId
                    serverCentrals[device.address] = device
                    updatePeerCount()
                    scope?.launch {
                        delay(800)
                        if (isActive && serverLinkIDs[device.address] == linkId) {
                            replayStoredPacketsToCentral(device)
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    serverLinkIDs.remove(device.address)
                    serverCentrals.remove(device.address)
                    updatePeerCount()
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (!isActive) return
                val hasActiveLink = serverLinkIDs.containsKey(device.address)
                if (!hasActiveLink) {
                    if (responseNeeded && MeshPermissions.hasBlePermissions(context)) {
                        runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null) }
                    }
                    return
                }
                if (responseNeeded && MeshPermissions.hasBlePermissions(context)) {
                    runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
                }
                if (characteristic.uuid == MESH_CHAR_UUID && value != null) {
                    handleIncomingFragment(value)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (responseNeeded && MeshPermissions.hasBlePermissions(context)) {
                    runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
                }
            }
        }) ?: return

        val char = BluetoothGattCharacteristic(
            MESH_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        char.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(char)
        server.addService(service)
        gattServer = server
        serverCharacteristic = char
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter) {
        if (!isActive || !adapter.isMultipleAdvertisementSupported || !MeshPermissions.hasBlePermissions(context)) return
        val adv = adapter.bluetoothLeAdvertiser ?: return
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(MESH_SERVICE_UUID), router.currentOriginTag)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        runCatching { adv.startAdvertising(settings, data, scanResponse, advertiseCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleAdvertiseRestart() {
        val meshScope = scope ?: return
        advertiseRetryCount++
        val delayMs = (ADVERTISE_RETRY_BASE_MS * advertiseRetryCount).coerceAtMost(ADVERTISE_MAX_RETRY_DELAY_MS)
        meshScope.launch {
            delay(delayMs)
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return@launch
            if (isActive && adapter.isEnabled && MeshPermissions.hasBlePermissions(context)) {
                runCatching { advertiser?.stopAdvertising(advertiseCallback) }
                delay(100)
                startAdvertising(adapter)
            }
        }
    }

    private fun buildScanConfig(): Pair<List<ScanFilter>, ScanSettings> =
        listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MESH_SERVICE_UUID)).build()) to
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()

    @SuppressLint("MissingPermission")
    private fun startDutyCycledScan(adapter: BluetoothAdapter, meshScope: CoroutineScope) {
        val leScanner = adapter.bluetoothLeScanner ?: return
        scanner = leScanner
        val (filters, settings) = buildScanConfig()

        scanLoopJob = meshScope.launch {
            while (isActive) {
                if (scanningDesired && adapter.isEnabled && MeshPermissions.hasBlePermissions(context)) {
                    startScanInternal(leScanner, filters, settings)
                    delay(SCAN_BURST_MS)
                    stopScanInternal(force = false)
                }
                delay(SCAN_IDLE_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal(leScanner: BluetoothLeScanner, filters: List<ScanFilter>, settings: ScanSettings) {
        if (!isActive || isCurrentlyScanning || !MeshPermissions.hasBlePermissions(context)) return
        val now = System.currentTimeMillis()
        if (now - lastScanStartTime < SCAN_RATE_LIMIT_MS) return
        lastScanStartTime = now
        isCurrentlyScanning = true
        runCatching {
            leScanner.startScan(filters, settings, scanCallback)
            _status.value = _status.value.copy(isScanning = true)
        }.onFailure {
            isCurrentlyScanning = false
            _status.value = _status.value.copy(isScanning = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal(force: Boolean) {
        if (!isCurrentlyScanning && !force) return
        if (MeshPermissions.hasBlePermissions(context)) {
            runCatching { scanner?.stopScan(scanCallback) }
        }
        isCurrentlyScanning = false
        _status.value = _status.value.copy(isScanning = false)
    }

    private fun scheduleScanRestart(baseDelayMs: Long) {
        val meshScope = scope ?: return
        scanRetryCount++
        val delayMs = (baseDelayMs * scanRetryCount).coerceAtMost(SCAN_MAX_RETRY_DELAY_MS)
        meshScope.launch {
            delay(delayMs)
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return@launch
            val leScanner = scanner ?: adapter.bluetoothLeScanner ?: return@launch
            scanner = leScanner
            if (isActive && scanningDesired && !isCurrentlyScanning) {
                val (filters, settings) = buildScanConfig()
                startScanInternal(leScanner, filters, settings)
            }
        }
    }

    private fun startScanWatchdog(adapter: BluetoothAdapter, meshScope: CoroutineScope) {
        watchdogJob = meshScope.launch {
            while (isActive) {
                delay(SCAN_WATCHDOG_INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (isActive && scanningDesired && adapter.isEnabled &&
                    now - lastScanResultTime > SCAN_STALE_RESULT_MS
                ) {
                    stopScanInternal(force = true)
                    delay(500)
                    val leScanner = scanner ?: adapter.bluetoothLeScanner ?: continue
                    scanner = leScanner
                    val (filters, settings) = buildScanConfig()
                    startScanInternal(leScanner, filters, settings)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAsClient(device: BluetoothDevice) {
        val addr = device.address ?: return
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (!isActive) {
                    runCatching { gatt.close() }
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    clientGatts[addr] = gatt
                    updatePeerCount()
                    runCatching { gatt.requestMtu(517) }
                    runCatching { gatt.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    clientGatts.remove(addr)
                    clientCharacteristics.remove(addr)
                    runCatching { gatt.close() }
                    updatePeerCount()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (!isActive || status != BluetoothGatt.GATT_SUCCESS) return
                val char = gatt.getService(MESH_SERVICE_UUID)?.getCharacteristic(MESH_CHAR_UUID) ?: return
                clientCharacteristics[addr] = char
                runCatching {
                    gatt.setCharacteristicNotification(char, true)
                    val cccd = char.getDescriptor(CCCD_UUID)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
                    }
                }
                scope?.launch { replayStoredPacketsToClient(gatt, char) }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == MESH_CHAR_UUID) handleIncomingFragment(value)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val v = characteristic.value ?: return
                if (characteristic.uuid == MESH_CHAR_UUID) handleIncomingFragment(v)
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    private fun handleIncomingFragment(chunkBytes: ByteArray) {
        val fullPacketBytes = fragmenter.onChunkReceived(chunkBytes) ?: return
        val decision = router.onIncomingBytes(
            rawBytes = fullPacketBytes,
            myDropId = myDropId,
            peerCount = _status.value.connectedPeers.coerceAtLeast(1)
        )
        if (decision.deliverLocally && decision.packet != null) {
            runCatching {
                val json = JSONObject(String(decision.packet.payload, Charsets.UTF_8))
                val senderDrop = json.optString("senderDropId", "")
                val ciphertext = json.optString("ciphertext", "")
                if (ciphertext.isNotBlank()) {
                    onEncryptedEnvelopeReceived(senderDrop, ciphertext)
                }
            }
        }
        val relay = decision.relayPacket
        if (relay != null) {
            _status.value = _status.value.copy(relayedCount = _status.value.relayedCount + 1)
            scope?.launch {
                // Bitchat jitter before rebroadcasting to avoid BLE collisions
                delay(Random.nextLong(8L, 26L))
                sendPacketToAllConnected(relay)
            }
        }
    }

    private suspend fun sendPacketToAllConnected(packet: MeshPacket) {
        val raw = packet.encode()
        if (!fragmenter.canFragment(raw.size)) return
        val chunks = fragmenter.fragment(raw)
        writeMutex.withLock {
            for ((addr, gatt) in clientGatts) {
                val char = clientCharacteristics[addr] ?: continue
                sendChunksWithDelay(chunks) { writeClientChunk(gatt, char, it) }
            }
            val sChar = serverCharacteristic
            if (sChar != null) {
                for (device in serverCentrals.values) {
                    sendChunksWithDelay(chunks) { notifyServerChunk(device, sChar, it) }
                }
            }
        }
    }

    private suspend fun replayStoredPacketsToCentral(device: BluetoothDevice) {
        val char = serverCharacteristic ?: return
        replayPendingLocked { chunk -> notifyServerChunk(device, char, chunk) }
    }

    private suspend fun replayStoredPacketsToClient(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        replayPendingLocked { chunk -> writeClientChunk(gatt, char, chunk) }
    }

    private suspend inline fun replayPendingLocked(crossinline transmit: (ByteArray) -> Unit) {
        val pending = router.pendingForwardPackets()
        if (pending.isEmpty()) return
        writeMutex.withLock {
            for (pkt in pending) {
                val raw = pkt.encode()
                if (!fragmenter.canFragment(raw.size)) continue
                sendChunksWithDelay(fragmenter.fragment(raw), transmit)
            }
        }
    }

    private suspend inline fun sendChunksWithDelay(chunks: List<ByteArray>, crossinline transmit: (ByteArray) -> Unit) {
        for (chunk in chunks) {
            transmit(chunk)
            delay(12)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeClientChunk(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, chunk: ByteArray) {
        if (!MeshPermissions.hasBlePermissions(context)) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                char.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyServerChunk(device: BluetoothDevice, char: BluetoothGattCharacteristic, chunk: ByteArray) {
        if (!MeshPermissions.hasBlePermissions(context)) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, char, false, chunk)
            } else {
                @Suppress("DEPRECATION")
                char.value = chunk
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
    }

    private fun updatePeerCount() {
        val uniqueCount = (serverCentrals.keys + clientGatts.keys).distinct().size
        _status.value = _status.value.copy(connectedPeers = uniqueCount)
    }
}
