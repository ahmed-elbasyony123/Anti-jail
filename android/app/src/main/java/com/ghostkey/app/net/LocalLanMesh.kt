package com.ghostkey.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline Local LAN Mesh Transport:
 *
 * Enables direct device-to-device end-to-end encrypted messaging over local Wi-Fi or portable
 * hotspot networks when the internet and Tor are completely censored or shut down.
 *
 * Zero cloud dependency, zero internet required.
 */
class LocalLanMesh(
    private val context: Context,
    private val onDirectMessageReceived: (senderDropId: String, ciphertext: String) -> Unit
) {
    companion object {
        private const val TAG = "LocalLanMesh"
        const val DEFAULT_MESH_PORT = 8766
        private const val BEACON_INTERVAL_MS = 10_000L
        private const val PEER_EXPIRY_MS = 60_000L
        // 4 MB line limit to allow encrypted attachments while strictly preventing DoS / OOM
        private const val MAX_LINE_LENGTH = 4 * 1024 * 1024
    }

    data class LanPeer(
        val dropId: String,
        val ipAddress: InetAddress,
        val port: Int,
        val lastSeen: Long
    )

    private var meshJob: CompletableJob? = null
    private var scope: CoroutineScope? = null
    private var isRunning = false
    private var myDropId: String = ""
    // Ephemeral node ID regenerated per session so persistent myDropId is never broadcast in cleartext over UDP
    private var ephemeralNodeId: String = UUID.randomUUID().toString().replace("-", "").take(16)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var udpSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null

    // In-memory peer directory: dropId -> LanPeer
    private val discoveredPeers = ConcurrentHashMap<String, LanPeer>()
    // Rate-limit mutual identity exchange to once every 30s per host
    private val lastExchangeAttempt = ConcurrentHashMap<String, Long>()
    private val connectionPermits = Semaphore(8)

    val activePeersCount: Int
        get() {
            pruneExpiredPeers()
            return discoveredPeers.size
        }

    fun getDiscoveredPeers(): List<LanPeer> {
        pruneExpiredPeers()
        return discoveredPeers.values.toList()
    }

    fun isPeerOnlineOnLan(dropId: String): Boolean {
        pruneExpiredPeers()
        return discoveredPeers.containsKey(dropId)
    }

    /**
     * Start the LAN mesh services: UDP discovery and TCP direct message listener.
     */
    fun start(myDropId: String) {
        if (isRunning && this.myDropId == myDropId) return
        this.myDropId = myDropId
        ephemeralNodeId = UUID.randomUUID().toString().replace("-", "").take(16)
        isRunning = true

        // Create a new Cancellable Job/Scope on every start so the mesh can be stopped and restarted cleanly
        meshJob?.cancel()
        val job = SupervisorJob()
        meshJob = job
        val currentScope = CoroutineScope(Dispatchers.IO + job)
        scope = currentScope

        acquireMulticastLock()
        startTcpServer(currentScope)
        startUdpListener(currentScope)
        startBeaconAnnouncer(currentScope)
    }

    /**
     * Stop all LAN mesh networking and release locks.
     */
    fun stop() {
        isRunning = false
        try {
            udpSocket?.close()
            udpSocket = null
        } catch (_: Exception) {}

        try {
            tcpServerSocket?.close()
            tcpServerSocket = null
        } catch (_: Exception) {}

        releaseMulticastLock()
        discoveredPeers.clear()
        lastExchangeAttempt.clear()

        // Cancel job and clear scope references so start() can initialize fresh
        meshJob?.cancel()
        meshJob = null
        scope = null
    }

    /**
     * Safe line reader that enforces a maximum character limit to prevent TCP port 8766 DoS/OOM attacks.
     */
    private fun readLineWithLimit(reader: BufferedReader, maxChars: Int = MAX_LINE_LENGTH): String? {
        val sb = StringBuilder()
        var charCount = 0
        while (true) {
            val c = reader.read()
            if (c == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (c == '\n'.code) {
                val str = sb.toString()
                return if (str.endsWith("\r")) str.dropLast(1) else str
            }
            sb.append(c.toChar())
            charCount++
            if (charCount > maxChars) {
                throw IOException("TCP line exceeded maximum limit of $maxChars characters")
            }
        }
    }

    /**
     * Direct delivery of encrypted OpenPGP ciphertext directly to peer's local IP:port.
     */
    suspend fun sendDirect(recipientDropId: String, ciphertext: String): Boolean = withContext(Dispatchers.IO) {
        pruneExpiredPeers()
        val peer = discoveredPeers[recipientDropId] ?: return@withContext false

        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(peer.ipAddress, peer.port), 3000)
                socket.soTimeout = 5000

                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Send JSON payload
                val envelope = JSONObject().apply {
                    put("type", "direct_mesh")
                    put("senderDropId", myDropId)
                    put("ciphertext", ciphertext)
                }
                writer.println(envelope.toString())

                val ack = readLineWithLimit(reader, 1024)
                return@withContext ack == "ACK"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed direct delivery to ${peer.ipAddress}: ${e.message}")
            false
        }
    }

    private fun startTcpServer(serviceScope: CoroutineScope) {
        serviceScope.launch {
            try {
                tcpServerSocket = ServerSocket(DEFAULT_MESH_PORT).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "LAN Direct TCP listener started on port $DEFAULT_MESH_PORT")

                while (isRunning && tcpServerSocket != null && !tcpServerSocket!!.isClosed) {
                    try {
                        val clientSocket = tcpServerSocket!!.accept()
                        handleClientConnection(serviceScope, clientSocket)
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting TCP mesh server: ${e.message}")
            }
        }
    }

    private fun handleClientConnection(serviceScope: CoroutineScope, socket: Socket) {
        if (!connectionPermits.tryAcquire()) {
            try { socket.close() } catch (_: Exception) {}
            return
        }
        serviceScope.launch {
            try {
                socket.use { s ->
                    s.soTimeout = 5000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val writer = PrintWriter(s.getOutputStream(), true)

                    val line = readLineWithLimit(reader, MAX_LINE_LENGTH)
                    if (!line.isNullOrBlank()) {
                        val json = JSONObject(line)
                        when (json.optString("type")) {
                            "direct_mesh" -> {
                                val senderDrop = json.optString("senderDropId")
                                val ciphertext = json.optString("ciphertext")

                                if (senderDrop.isNotBlank() && ciphertext.isNotBlank()) {
                                    writer.println("ACK")
                                    withContext(Dispatchers.Main) {
                                        onDirectMessageReceived(senderDrop, ciphertext)
                                    }
                                    return@launch
                                }
                            }
                            "mesh_id_exchange" -> {
                                val peerDrop = json.optString("dropId")
                                if (peerDrop.isNotBlank() && peerDrop != myDropId) {
                                    val peerPort = json.optInt("port", DEFAULT_MESH_PORT)
                                    discoveredPeers[peerDrop] = LanPeer(
                                        dropId = peerDrop,
                                        ipAddress = s.inetAddress,
                                        port = peerPort,
                                        lastSeen = System.currentTimeMillis()
                                    )
                                    val reply = JSONObject().apply {
                                        put("type", "mesh_id_exchange_ack")
                                        put("dropId", myDropId)
                                        put("port", DEFAULT_MESH_PORT)
                                    }
                                    writer.println(reply.toString())
                                    return@launch
                                }
                            }
                        }
                    }
                    writer.println("REJECT")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error handling direct LAN connection: ${e.message}")
            } finally {
                connectionPermits.release()
            }
        }
    }

    private fun startUdpListener(serviceScope: CoroutineScope) {
        serviceScope.launch {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DEFAULT_MESH_PORT))
                }
                val buffer = ByteArray(1024)

                while (isRunning && udpSocket != null && !udpSocket!!.isClosed) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        udpSocket!!.receive(packet)

                        val data = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        // Safe beacon discovery (ephemeral node ID over UDP, no cleartext persistent dropId)
                        if (data.startsWith("GHOSTKEY:BEACON:")) {
                            val parts = data.split(":")
                            if (parts.size >= 4) {
                                val peerNodeId = parts[2]
                                val peerPort = parts[3].toIntOrNull() ?: DEFAULT_MESH_PORT
                                if (peerNodeId.isNotBlank() && peerNodeId != ephemeralNodeId) {
                                    initiateIdentityExchange(serviceScope, packet.address, peerPort)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in UDP LAN listener: ${e.message}")
            }
        }
    }

    /**
     * Authenticated point-to-point TCP identity exchange triggered by safe UDP beacon.
     * Prevents broadcast eavesdropping of persistent myDropId while securely discovering LAN peers.
     */
    private fun initiateIdentityExchange(serviceScope: CoroutineScope, address: InetAddress, port: Int) {
        val host = address.hostAddress ?: return
        val now = System.currentTimeMillis()
        if (lastExchangeAttempt.size > 256) {
            lastExchangeAttempt.entries.removeIf { now - it.value > 5 * 60_000L }
        }
        val last = lastExchangeAttempt[host] ?: 0L
        if (now - last < 30_000L) return
        lastExchangeAttempt[host] = now

        serviceScope.launch {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), 3000)
                    socket.soTimeout = 5000

                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    val req = JSONObject().apply {
                        put("type", "mesh_id_exchange")
                        put("dropId", myDropId)
                        put("port", DEFAULT_MESH_PORT)
                    }
                    writer.println(req.toString())

                    val responseLine = readLineWithLimit(reader, 4096)
                    if (!responseLine.isNullOrBlank()) {
                        val json = JSONObject(responseLine)
                        if (json.optString("type") == "mesh_id_exchange_ack") {
                            val peerDrop = json.optString("dropId")
                            val peerPort = json.optInt("port", DEFAULT_MESH_PORT)
                            if (peerDrop.isNotBlank() && peerDrop != myDropId) {
                                discoveredPeers[peerDrop] = LanPeer(
                                    dropId = peerDrop,
                                    ipAddress = address,
                                    port = peerPort,
                                    lastSeen = System.currentTimeMillis()
                                )
                                Log.d(TAG, "Discovered LAN peer via safe exchange: $peerDrop at $address:$peerPort")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Identity exchange with $address:$port failed: ${e.message}")
            }
        }
    }

    private fun startBeaconAnnouncer(serviceScope: CoroutineScope) {
        serviceScope.launch {
            while (isRunning) {
                try {
                    if (myDropId.isNotBlank()) {
                        // Announce using ephemeral session node ID instead of persistent cleartext myDropId
                        val beaconMsg = "GHOSTKEY:BEACON:$ephemeralNodeId:$DEFAULT_MESH_PORT"
                        val bytes = beaconMsg.toByteArray(Charsets.UTF_8)

                        val broadcastAddresses = getBroadcastAddresses()
                        DatagramSocket().use { sendSocket ->
                            sendSocket.broadcast = true
                            for (addr in broadcastAddresses) {
                                try {
                                    val packet = DatagramPacket(bytes, bytes.size, addr, DEFAULT_MESH_PORT)
                                    sendSocket.send(packet)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error sending LAN beacon: ${e.message}")
                }
                delay(BEACON_INTERVAL_MS)
            }
        }
    }

    /**
     * Resolves broadcast addresses on valid local Wi-Fi / LAN interfaces only.
     * Excludes VPNs (tun, tap, ppp), cellular (rmnet, ccmni), and virtual interfaces to prevent privacy leaks.
     */
    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp || netIf.isVirtual) continue
                val name = netIf.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") ||
                    name.startsWith("p2p") || name.startsWith("dummy") || name.contains("vpn") ||
                    name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("usbnet")) {
                    continue
                }
                for (interfaceAddress in netIf.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && !broadcast.isLoopbackAddress && !broadcast.isAnyLocalAddress) {
                        list.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {}
        return list.distinct()
    }

    private fun pruneExpiredPeers() {
        val now = System.currentTimeMillis()
        val it = discoveredPeers.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value.lastSeen > PEER_EXPIRY_MS) {
                it.remove()
            }
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("ghostkey_lan_mesh")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                while (it.isHeld) {
                    it.release()
                }
            }
            multicastLock = null
        } catch (_: Exception) {}
    }
}
