package com.ghostkey.app.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.*
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
    }

    data class LanPeer(
        val dropId: String,
        val ipAddress: InetAddress,
        val port: Int,
        val lastSeen: Long
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private var myDropId: String = ""

    private var multicastLock: WifiManager.MulticastLock? = null
    private var udpSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null

    // In-memory peer directory: dropId -> LanPeer
    private val discoveredPeers = ConcurrentHashMap<String, LanPeer>()

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
        isRunning = true

        acquireMulticastLock()
        startTcpServer()
        startUdpListener()
        startBeaconAnnouncer()
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
        scope.cancel()
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

                val ack = reader.readLine()
                return@withContext ack == "ACK"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed direct delivery to ${peer.ipAddress}: ${e.message}")
            false
        }
    }

    private fun startTcpServer() {
        scope.launch {
            try {
                tcpServerSocket = ServerSocket(DEFAULT_MESH_PORT).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "LAN Direct TCP listener started on port $DEFAULT_MESH_PORT")

                while (isRunning && tcpServerSocket != null && !tcpServerSocket!!.isClosed) {
                    try {
                        val clientSocket = tcpServerSocket!!.accept()
                        handleClientConnection(clientSocket)
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting TCP mesh server: ${e.message}")
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        scope.launch {
            try {
                socket.use { s ->
                    s.soTimeout = 5000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val writer = PrintWriter(s.getOutputStream(), true)

                    val line = reader.readLine()
                    if (!line.isNullOrBlank()) {
                        val json = JSONObject(line)
                        if (json.optString("type") == "direct_mesh") {
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
                    }
                    writer.println("REJECT")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error handling direct LAN connection: ${e.message}")
            }
        }
    }

    private fun startUdpListener() {
        scope.launch {
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
                        if (data.startsWith("GHOSTKEY:ANNOUNCE:")) {
                            val parts = data.split(":")
                            if (parts.size >= 4) {
                                val peerDropId = parts[2]
                                val peerPort = parts[3].toIntOrNull() ?: DEFAULT_MESH_PORT

                                if (peerDropId.isNotBlank() && peerDropId != myDropId) {
                                    discoveredPeers[peerDropId] = LanPeer(
                                        dropId = peerDropId,
                                        ipAddress = packet.address,
                                        port = peerPort,
                                        lastSeen = System.currentTimeMillis()
                                    )
                                    Log.d(TAG, "Discovered LAN peer: $peerDropId at ${packet.address}:$peerPort")
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

    private fun startBeaconAnnouncer() {
        scope.launch {
            while (isRunning) {
                try {
                    if (myDropId.isNotBlank()) {
                        val beaconMsg = "GHOSTKEY:ANNOUNCE:$myDropId:$DEFAULT_MESH_PORT"
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

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue
                for (interfaceAddress in netIf.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
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
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("ghostkey_lan_mesh")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
            multicastLock = null
        } catch (_: Exception) {}
    }
}
