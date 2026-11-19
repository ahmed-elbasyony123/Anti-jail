package com.ghostkey.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private var activeDropId: String? = null
    private var activeReadToken: String? = null
    private var messageListener: ((EncryptedMessageRecord) -> Unit)? = null
    private var statusListener: ((RelayStatus) -> Unit)? = null

    // ---------------------------------------------------------------------------------------
    // Configuration
    // ---------------------------------------------------------------------------------------

    private fun buildClient(): OkHttpClient {
        val torActive = useTorProxy
        val builder = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)

        if (torActive) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(torSocksHost, torSocksPort)))
        }

        // With a SOCKS proxy OkHttp hands the host name to the proxy (Tor resolves it). This
        // resolver is a second line of defence: while Tor is on, any attempt to resolve a name
        // locally fails instead of leaking a DNS query outside Tor.
        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (torActive) {
                    throw UnknownHostException("Local DNS lookups are disabled while the Tor proxy is on")
                }
                return Dns.SYSTEM.lookup(hostname)
            }
        })
        return builder.build()
    }

    /** Accepts a relay URL typed by a person: adds https:// (or http:// for .onion/loopback) when missing and trims slashes. */
    private fun sanitizeUrl(url: String): String {
        var clean = url.trim()
        if (clean.isNotEmpty() && !clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = if (clean.contains(".onion") || clean.startsWith("127.0.0.1") || clean.startsWith("localhost") || clean.startsWith("10.0.2.2")) {
                "http://$clean"
            } else {
                "https://$clean"
            }
        }
        return clean.trimEnd('/')
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
        serverBaseUrl = sanitizeUrl(url)
        useTorProxy = useTor || serverBaseUrl.contains(".onion")
        torSocksHost = socksHost
        torSocksPort = socksPort
        httpClient = buildClient()

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

    /** POST /api/v2/send. Returns the relay's message id. */
    suspend fun sendMessage(dropId: String, ciphertext: String, ttlSeconds: Int = 3600): Result<String> =
        withContext(Dispatchers.IO) {
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
            try {
                val ids = JSONArray()
                messageIds.forEach { ids.put(it) }
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
                    Result.success(json.optInt("removed", 0))
                }
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
        this.activeDropId = dropId
        this.activeReadToken = readToken
        this.messageListener = onMessageReceived
        this.statusListener = onStatusChanged
        this.isSubscribed = true
        this.authFailed = false
        this.reconnectAttempt = 0
        startWebSocketConnection()
    }

    /**
     * Confirm receipt. Uses the open WebSocket when possible, otherwise HTTP. If the ACK is
     * lost the relay simply delivers the message again, which the caller de-duplicates.
     */
    fun ack(messageIds: List<String>) {
        val dropId = activeDropId ?: return
        val token = activeReadToken ?: return
        if (messageIds.isEmpty()) return
        val ws = currentWebSocket
        if (ws != null && subscriptionConfirmed) {
            val ids = JSONArray()
            messageIds.forEach { ids.put(it) }
            val sent = ws.send(
                JSONObject().put("type", "ACK").put("dropId", dropId).put("messageIds", ids).toString()
            )
            if (sent) return
        }
        scope.launch { ackMessages(dropId, token, messageIds) }
    }

    /** Stop everything: no further reconnects. */
    fun disconnectWebSocket() {
        isSubscribed = false
        reconnectJob?.cancel()
        reconnectJob = null
        closeSocketOnly()
    }

    /**
     * Close the current socket without touching the subscription state, so the auto-reconnect
     * logic keeps working. (Bumping the generation makes late callbacks of the old socket
     * harmless.)
     */
    private fun closeSocketOnly() {
        socketGeneration.incrementAndGet()
        subscriptionConfirmed = false
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
        val token = activeReadToken ?: return

        val request = try {
            Request.Builder().url(buildWsUrl()).build()
        } catch (e: Exception) {
            emit(RelayStatus.DISCONNECTED)
            return
        }

        emit(if (useTorProxy) RelayStatus.BUILDING_CIRCUIT else RelayStatus.DISCONNECTED)

        val generation = socketGeneration.incrementAndGet()
        currentWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(generation)) return
                reconnectAttempt = 0                   // success resets the backoff to 4 s
                webSocket.send(
                    JSONObject()
                        .put("type", "SUBSCRIBE")
                        .put("dropId", dropId)
                        .put("readToken", token)
                        .toString()
                )
                // The relay also pushes its backlog after SUBSCRIBE; polling as well makes sure
                // nothing queued while we were offline is missed. Duplicates are de-duplicated
                // by id on the caller side.
                pollPending(generation, dropId, token)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(generation)) return
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "SUBSCRIBED" -> {
                            subscriptionConfirmed = true
                            emit(RelayStatus.CONNECTED)
                        }
                        "AUTH_FAILED" -> handleAuthFailed()
                        "MESSAGE" -> {
                            val obj = json.optJSONObject("message") ?: return
                            messageListener?.invoke(parseMessage(dropId, obj))
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
                emit(RelayStatus.DISCONNECTED)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(generation)) return
                socketGeneration.incrementAndGet()
                subscriptionConfirmed = false
                emit(classifyFailure(t))
                scheduleReconnect()
            }
        })
    }

    private fun pollPending(generation: Int, dropId: String, token: String) {
        scope.launch {
            val result = fetchPendingMessages(dropId, token)
            if (!isSubscribed) return@launch
            result.onSuccess { page ->
                page.messages.forEach { messageListener?.invoke(it) }
            }.onFailure { e ->
                if (e is RelayHttpException && e.code == 401) handleAuthFailed()
            }
        }
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
