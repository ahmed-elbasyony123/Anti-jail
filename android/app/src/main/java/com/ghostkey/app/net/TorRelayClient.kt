package com.ghostkey.app.net

import com.ghostkey.app.util.GhostKeyUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What the UI shows about the relay connection. */
enum class RelayStatus {
    DISCONNECTED,
    ORBOT_NOT_RUNNING,
    BUILDING_CIRCUIT,
    CONNECTED,
    TIMED_OUT,
    AUTH_FAILED
}

/** A message as stored on the relay: still encrypted, until the app decrypts it. */
data class EncryptedMessageRecord(
    val id: String,
    val dropId: String,
    val ciphertext: String,
    val createdAt: Long
)

data class InboxPage(val messages: List<EncryptedMessageRecord>, val more: Boolean)

class RelayHttpException(val code: Int) : IOException("Relay returned HTTP $code")

/**
 * Relay client for wire protocol v2.
 *
 * - Mailboxes are addressed by random drop IDs. Reading needs the secret read token, sent as
 *   the X-Mailbox-Token header (HTTP) or the readToken field of SUBSCRIBE (WebSocket).
 * - The relay keeps messages until they are ACKed, so delivery is at-least-once: callers must
 *   de-duplicate by message id and call [ack] only after the message has been processed.
 * - When the Tor proxy is on, every connection goes through Orbot's SOCKS5 port and host names
 *   are resolved by Tor; local DNS lookups are refused.
 */
class TorRelayClient(
    var serverBaseUrl: String = "http://127.0.0.1:8765",
    var useTorProxy: Boolean = false,
    var torSocksHost: String = "127.0.0.1",
    var torSocksPort: Int = OrbotPortWatcher.DEFAULT_SOCKS_PORT
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var httpClient: OkHttpClient = buildClient()

    private var currentWebSocket: WebSocket? = null
    private val socketGeneration = AtomicInteger(0)

    @Volatile private var isSubscribed = false
    @Volatile private var authFailed = false
    @Volatile private var subscriptionConfirmed = false
    @Volatile private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var handshakeWatchdogJob: Job? = null

    private var activeDropId: String? = null
    private var activeReadToken: String? = null
    private val activeSubscriptions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var messageListener: ((EncryptedMessageRecord) -> Unit)? = null
    private var statusListener: ((RelayStatus) -> Unit)? = null
    @Volatile var lastConnectionError: String? = null
        private set

    // ---------------------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------------------

    private fun buildClient(): OkHttpClient {
        val torActive = useTorProxy || serverBaseUrl.contains(".onion")
        val builder = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)

        if (torActive) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(torSocksHost, torSocksPort)))
        }

        // DNS resolver: Prevent clearnet DNS leaks by never calling Dns.SYSTEM.lookup when Tor is active.
        // With SOCKS proxy set, OkHttp delegates remote host resolution (including .onion) to Tor via
        // InetSocketAddress.createUnresolved(host, port), avoiding any routing to 1.1.1.1.
        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (torActive) {
                    if (hostname.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) || hostname == "localhost" || hostname == "::1") {
                        return listOf(InetAddress.getByName(hostname))
                    }
                    throw UnknownHostException("Direct DNS lookup for '$hostname' is blocked when Tor is active to prevent clearnet DNS leaks")
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        })
        return builder.build()
    }

    private fun closeHttpClient(client: OkHttpClient) {
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            client.cache?.close()
        } catch (_: Exception) {}
    }

    /**
     * Apply new network settings. If a live subscription exists it is restarted with the new
     * settings. Passing [useTor] = false is only honoured for non-.onion hosts.
     */
    fun updateConfig(
        url: String,
        useTor: Boolean,
        socksHost: String = torSocksHost,
        socksPort: Int = torSocksPort
    ) {
        val cleanUrl = GhostKeyUtils.sanitizeRelayUrl(url)
        val effectiveTor = useTor || cleanUrl.contains(".onion")
        if (
            cleanUrl == serverBaseUrl &&
            effectiveTor == useTorProxy &&
            socksHost == torSocksHost &&
            socksPort == torSocksPort
        ) {
            return
        }
        serverBaseUrl = cleanUrl
        useTorProxy = effectiveTor
        torSocksHost = socksHost
        torSocksPort = socksPort
        val oldClient = httpClient
        httpClient = buildClient()
        closeHttpClient(oldClient)

        if (isSubscribed) {
            authFailed = false
            reconnectAttempt = 0
            startWebSocketConnection()
        }
    }

    private fun emit(status: RelayStatus) {
        statusListener?.invoke(status)
    }

    // ---------------------------------------------------------------------------------------
    // HTTP API
    // ---------------------------------------------------------------------------------------

    /** GET /api/v2/ping: verifies relay reachability (routing via Tor SOCKS5 when enabled or .onion). */
    suspend fun pingRelay(rawUrl: String = serverBaseUrl): Result<Long> =
        withContext(Dispatchers.IO) {
            val cleanUrl = GhostKeyUtils.sanitizeRelayUrl(rawUrl)
            if (cleanUrl.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Relay URL is empty"))
            }
            val needsTor = useTorProxy || cleanUrl.contains(".onion", ignoreCase = true)
            val client = if (needsTor == useTorProxy && cleanUrl == serverBaseUrl) {
                httpClient
            } else {
                OkHttpClient.Builder()
                    .connectTimeout(25, TimeUnit.SECONDS)
                    .readTimeout(25, TimeUnit.SECONDS)
                    .apply {
                        if (needsTor) {
                            proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(torSocksHost, torSocksPort)))
                            dns(object : Dns {
                                override fun lookup(hostname: String): List<InetAddress> {
                                    if (hostname.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$""")) || hostname == "localhost" || hostname == "::1") {
                                        return listOf(InetAddress.getByName(hostname))
                                    }
                                    throw UnknownHostException("Direct DNS lookup blocked when Tor is active")
                                }
                            })
                        }
                    }
                    .build()
            }
            try {
                val started = System.currentTimeMillis()
                val request = Request.Builder().url("$cleanUrl/api/v2/ping").get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw RelayHttpException(response.code)
                    Result.success((System.currentTimeMillis() - started).coerceAtLeast(1L))
                }
            } catch (e: Exception) {
                Result.failure(e)
        } finally {
            if (client !== httpClient) closeHttpClient(client)
        }
    }

    /** POST /api/v2/rendezvous: creates an ephemeral E2EE pairing slot (default 5 min TTL). */
    suspend fun createRendezvous(slotId: String, sealedBlob: String, ttlSeconds: Int = 300): Result<Long> =
        withContext(Dispatchers.IO) {
            if (serverBaseUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Relay URL is not set in Settings"))
            }
            try {
                val body = JSONObject()
                    .put("slotId", slotId)
                    .put("sealedBlob", sealedBlob)
                    .put("ttlSeconds", ttlSeconds)
                    .toString()
                val req = Request.Builder()
                    .url("$serverBaseUrl/api/v2/rendezvous")
                    .post(body.toRequestBody(jsonType))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw RelayHttpException(resp.code)
                    val expiresAt = runCatching { JSONObject(raw).optLong("expiresAt", System.currentTimeMillis() + ttlSeconds * 1000L) }
                        .getOrDefault(System.currentTimeMillis() + ttlSeconds * 1000L)
                    Result.success(expiresAt)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** GET /api/v2/rendezvous/:slotId: atomic burn-after-reading fetch of an encrypted pairing slot. */
    suspend fun claimRendezvous(slotId: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (serverBaseUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Relay URL is not set in Settings"))
            }
            try {
                val req = Request.Builder()
                    .url("$serverBaseUrl/api/v2/rendezvous/$slotId")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RelayHttpException(resp.code)
                    val raw = resp.body?.string().orEmpty()
                    val sealedBlob = JSONObject(raw).optString("sealedBlob", "")
                    if (sealedBlob.isBlank()) throw IOException("Empty rendezvous payload")
                    Result.success(sealedBlob)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** POST /api/v2/send. Returns the relay's message id. */
    suspend fun sendMessage(dropId: String, ciphertext: String, ttlSeconds: Int = 3600): Result<String> =
        withContext(Dispatchers.IO) {
            if (serverBaseUrl.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Relay URL is not set in Settings"))
            }
            try {
                val body = JSONObject()
                    .put("dropId", dropId)
                    .put("ciphertext", ciphertext)
                    .put("ttlSeconds", ttlSeconds)
                    .toString()
                    .toRequestBody(jsonType)
                val request = Request.Builder().url("$serverBaseUrl/api/v2/send").post(body).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw RelayHttpException(response.code)
                    val json = JSONObject(response.body?.string() ?: "{}")
                    Result.success(json.optString("messageId", ""))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** GET /api/v2/inbox/:dropId with X-Mailbox-Token. Does not delete anything on the relay. */
    suspend fun fetchPendingMessages(dropId: String, readToken: String): Result<InboxPage> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverBaseUrl/api/v2/inbox/$dropId")
                    .header("X-Mailbox-Token", readToken)
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw RelayHttpException(response.code)
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val array = json.optJSONArray("messages") ?: JSONArray()
                    val list = ArrayList<EncryptedMessageRecord>(array.length())
                    for (i in 0 until array.length()) {
                        list.add(parseMessage(dropId, array.getJSONObject(i)))
                    }
                    Result.success(InboxPage(list, json.optBoolean("more", false)))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** POST /api/v2/ack: tells the relay these messages were received and may be deleted. */
    suspend fun ackMessages(dropId: String, readToken: String, messageIds: List<String>): Result<Int> =
        withContext(Dispatchers.IO) {
            if (messageIds.isEmpty()) return@withContext Result.success(0)
            try {
                var totalRemoved = 0
                for (chunk in messageIds.chunked(1000)) {
                    val ids = JSONArray()
                    chunk.forEach { ids.put(it) }
                    val body = JSONObject()
                        .put("dropId", dropId)
                        .put("messageIds", ids)
                        .toString()
                        .toRequestBody(jsonType)
                    val request = Request.Builder()
                        .url("$serverBaseUrl/api/v2/ack")
                        .header("X-Mailbox-Token", readToken)
                        .post(body)
                        .build()
                    httpClient.newCall(request).execute().use { response: Response ->
                        if (!response.isSuccessful) throw RelayHttpException(response.code)
                        val json = JSONObject(response.body?.string() ?: "{}")
                        totalRemoved += json.optInt("removed", 0)
                    }
                }
                Result.success(totalRemoved)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun parseMessage(dropId: String, obj: JSONObject): EncryptedMessageRecord =
        EncryptedMessageRecord(
            id = obj.getString("id"),
            dropId = dropId,
            ciphertext = obj.getString("ciphertext"),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        )

    // ---------------------------------------------------------------------------------------
    // Live subscription (WebSocket)
    // ---------------------------------------------------------------------------------------

    /**
     * Subscribe to a mailbox and keep the connection alive (with backoff) until
     * [disconnectWebSocket] is called. Messages arrive via [onMessageReceived]; call [ack]
     * once a message has been decrypted and stored.
     */
    fun subscribeLive(
        dropId: String,
        readToken: String,
        onMessageReceived: (EncryptedMessageRecord) -> Unit,
        onStatusChanged: (RelayStatus) -> Unit
    ) {
        this.activeSubscriptions.clear()
        this.activeDropId = dropId
        this.activeReadToken = readToken
        this.activeSubscriptions[dropId] = readToken
        this.messageListener = onMessageReceived
        this.statusListener = onStatusChanged
        this.isSubscribed = true
        this.authFailed = false
        this.reconnectAttempt = 0
        startWebSocketConnection()
    }

    /** Add an extra mailbox (e.g. for group chats) to listen to on the same live connection. */
    fun addSubscription(dropId: String, readToken: String) {
        activeSubscriptions[dropId] = readToken
        val ws = currentWebSocket
        if (ws != null && subscriptionConfirmed) {
            ws.send(
                JSONObject()
                    .put("type", "SUBSCRIBE")
                    .put("dropId", dropId)
                    .put("readToken", readToken)
                    .toString()
            )
        }
    }

    /** Zero out secrets from memory on Panic Wipe. */
    fun wipe() {
        disconnectWebSocket()
        activeDropId = null
        activeReadToken = null
        activeSubscriptions.clear()
        messageListener = null
        statusListener = null
    }

    /**
     * Confirm receipt. Uses the open WebSocket when possible, otherwise HTTP. If the ACK is
     * lost the relay simply delivers the message again, which the caller de-duplicates.
     * Chunks ACKs into batches of at most 1000 IDs to respect server limits.
     */
    fun ack(messageIds: List<String>, dropId: String? = null) {
        val targetDropId = dropId ?: activeDropId ?: return
        val token = activeSubscriptions[targetDropId] ?: activeReadToken ?: return
        if (messageIds.isEmpty()) return
        val chunks = messageIds.chunked(1000)
        val ws = currentWebSocket
        for (chunk in chunks) {
            if (ws != null && subscriptionConfirmed) {
                val ids = JSONArray()
                chunk.forEach { ids.put(it) }
                val sent = ws.send(
                    JSONObject().put("type", "ACK").put("dropId", targetDropId).put("messageIds", ids).toString()
                )
                if (sent) continue
            }
            scope.launch { ackMessages(targetDropId, token, chunk) }
        }
    }

    /** Stop everything: no further reconnects. Clean up connection pool. */
    fun disconnectWebSocket() {
        isSubscribed = false
        reconnectJob?.cancel()
        reconnectJob = null
        handshakeWatchdogJob?.cancel()
        handshakeWatchdogJob = null
        closeSocketOnly()
        try {
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {}
    }

    /** Permanently release the HTTP thread pool and coroutine scope on ViewModel teardown. */
    fun close() {
        wipe()
        closeHttpClient(httpClient)
        scope.cancel()
    }

    /**
     * Close the current socket without touching the subscription state, so the auto-reconnect
     * logic keeps working. (Bumping the generation makes late callbacks of the old socket
     * harmless.)
     */
    private fun closeSocketOnly() {
        socketGeneration.incrementAndGet()
        subscriptionConfirmed = false
        handshakeWatchdogJob?.cancel()
        handshakeWatchdogJob = null
        val ws = currentWebSocket
        currentWebSocket = null
        try {
            ws?.close(1000, "closing")
        } catch (_: Exception) {
        }
    }

    private fun isCurrent(generation: Int) = generation == socketGeneration.get()

    private fun buildWsUrl(): String {
        val base = when {
            serverBaseUrl.startsWith("https://") -> "wss://" + serverBaseUrl.removePrefix("https://")
            else -> "ws://" + serverBaseUrl.removePrefix("http://")
        }
        return "$base/api/v2/live"
    }

    private fun startWebSocketConnection() {
        closeSocketOnly()                              // NOT disconnectWebSocket(): keep isSubscribed
        val dropId = activeDropId ?: return
        if (activeReadToken == null || serverBaseUrl.isBlank()) {
            emit(RelayStatus.DISCONNECTED)
            return
        }

        val request = try {
            Request.Builder().url(buildWsUrl()).build()
        } catch (e: Exception) {
            emit(RelayStatus.DISCONNECTED)
            return
        }

        emit(if (useTorProxy) RelayStatus.BUILDING_CIRCUIT else RelayStatus.DISCONNECTED)

        val generation = socketGeneration.incrementAndGet()
        handshakeWatchdogJob?.cancel()
        handshakeWatchdogJob = scope.launch {
            delay(25_000L)
            if (isCurrent(generation) && !subscriptionConfirmed && isSubscribed && !authFailed) {
                lastConnectionError = "WebSocket handshake stalled after 25s; resetting Tor stream"
                android.util.Log.w("GhostKey-Relay", lastConnectionError!!)
                val hung = currentWebSocket
                currentWebSocket = null
                socketGeneration.incrementAndGet()
                try { hung?.cancel() } catch (_: Exception) {}
                emit(RelayStatus.TIMED_OUT)
                scheduleReconnect()
            }
        }
        currentWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(generation)) return
                handshakeWatchdogJob?.cancel()
                handshakeWatchdogJob = null
                reconnectAttempt = 0                   // success resets the backoff
                for ((dId, rToken) in activeSubscriptions) {
                    webSocket.send(
                        JSONObject()
                            .put("type", "SUBSCRIBE")
                            .put("dropId", dId)
                            .put("readToken", rToken)
                            .toString()
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(generation)) return
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "SUBSCRIBED" -> {
                            lastConnectionError = null
                            subscriptionConfirmed = true
                            emit(RelayStatus.CONNECTED)
                        }
                        "AUTH_FAILED" -> handleAuthFailed()
                        "MESSAGE" -> {
                            val msgDropId = json.optString("dropId", dropId)
                            val obj = json.optJSONObject("message") ?: return
                            messageListener?.invoke(parseMessage(msgDropId, obj))
                        }
                        else -> { /* ACKED, PONG, ERROR: nothing to do */ }
                    }
                } catch (_: Exception) {
                    // ignore malformed frames
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(generation)) return
                socketGeneration.incrementAndGet()     // later events of this socket are ignored
                subscriptionConfirmed = false
                lastConnectionError = "WebSocket closed ($code ${reason.ifBlank { "no reason" }})"
                emit(RelayStatus.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(generation)) return
                socketGeneration.incrementAndGet()
                subscriptionConfirmed = false
                val httpDetail = response?.let { " HTTP ${it.code}" }.orEmpty()
                lastConnectionError = "${t.javaClass.simpleName}$httpDetail: ${t.message ?: "unknown error"}"
                android.util.Log.w("GhostKey-Relay", "WebSocket failure ($serverBaseUrl): $lastConnectionError")
                emit(classifyFailure(t))
                scheduleReconnect()
            }
        })
    }

    private fun handleAuthFailed() {
        // The relay has this mailbox claimed with a different token: retrying cannot help.
        authFailed = true
        reconnectJob?.cancel()
        closeSocketOnly()
        emit(RelayStatus.AUTH_FAILED)
    }

    private fun classifyFailure(t: Throwable): RelayStatus {
        val message = t.message.orEmpty()
        return when {
            t is SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                RelayStatus.TIMED_OUT
            // If embedded Arti Tor is available, connection retries while circuit builds should report BUILDING_CIRCUIT
            useTorProxy && ArtiTorManager.isAvailable -> {
                if (t is SocketException && message.contains("SOCKS", ignoreCase = true)) {
                    RelayStatus.BUILDING_CIRCUIT
                } else if (t is ConnectException) {
                    if (torSocksPort != OrbotPortWatcher.DEFAULT_SOCKS_PORT || ArtiTorManager.state.value is ArtiTorState.Starting) {
                        RelayStatus.BUILDING_CIRCUIT
                    } else {
                        RelayStatus.ORBOT_NOT_RUNNING
                    }
                } else {
                    RelayStatus.DISCONNECTED
                }
            }
            // Cannot even reach Orbot's local SOCKS port: Orbot is not running.
            useTorProxy && t is ConnectException -> RelayStatus.ORBOT_NOT_RUNNING
            // Orbot answered but Tor could not reach the relay yet (no circuit / descriptor).
            useTorProxy && t is SocketException && message.contains("SOCKS", ignoreCase = true) ->
                RelayStatus.BUILDING_CIRCUIT
            else -> RelayStatus.DISCONNECTED
        }
    }

    private fun scheduleReconnect() {
        if (!isSubscribed || authFailed) return
        reconnectJob?.cancel()
        val wait = Backoff.delayMs(reconnectAttempt)
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(wait)
            if (isSubscribed && !authFailed) startWebSocketConnection()
        }
    }
}
