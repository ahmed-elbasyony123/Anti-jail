package com.ghostkey.app.net

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

data class EncryptedMessageRecord(
    val id: String,
    val ciphertext: String,
    val createdAt: Long,
    var decryptedPlaintext: String? = null
)

class TorRelayClient(
    var serverBaseUrl: String = "http://127.0.0.1:8765",
    var useTorProxy: Boolean = false,
    var torSocksHost: String = "127.0.0.1",
    var torSocksPort: Int = 9050
) {
    private var httpClient: OkHttpClient = buildClient()
    private var currentWebSocket: WebSocket? = null

    // Reconnection & Subscription state
    private var isSubscribed = false
    private var activeMailboxHash: String? = null
    private var messageListener: ((EncryptedMessageRecord) -> Unit)? = null
    private var statusListener: ((Boolean) -> Unit)? = null
    private var reconnectJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // Custom DNS to prevent local DNS leak for .onion addresses
        // Forces SOCKS5 proxy to resolve .onion remotely via Tor network
        builder.dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (hostname.endsWith(".onion", ignoreCase = true)) {
                    listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        })

        if (useTorProxy) {
            val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(torSocksHost, torSocksPort))
            builder.proxy(socksProxy)
        }

        return builder.build()
    }

    fun updateConfig(baseUrl: String, useTor: Boolean, host: String = "127.0.0.1", port: Int = 9050) {
        var cleanUrl = baseUrl.trim().trimEnd('/')
        if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://$cleanUrl"
        }
        this.serverBaseUrl = if (cleanUrl.isBlank()) "http://10.0.2.2:8765" else cleanUrl
        this.useTorProxy = useTor || cleanUrl.contains(".onion", ignoreCase = true)
        this.torSocksHost = host
        this.torSocksPort = port
        this.httpClient = buildClient()

        // If currently subscribed, reconnect with new client configuration
        if (isSubscribed && activeMailboxHash != null) {
            startWebSocketConnection()
        }
    }

    /**
     * Send an armored PGP ciphertext to the blind mailbox.
     */
    suspend fun sendMessage(mailboxHash: String, ciphertext: String, ttlSeconds: Int = 3600): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("mailboxHash", mailboxHash)
                    put("ciphertext", ciphertext)
                    put("ttlSeconds", ttlSeconds)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = json.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("$serverBaseUrl/api/v1/send")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IOException("Server error: ${response.code}"))
                    }
                    val respJson = JSONObject(response.body?.string() ?: "{}")
                    val msgId = respJson.optString("messageId", "ok")
                    Result.success(msgId)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Fetch all waiting messages from the blind mailbox and BURN them immediately on the server.
     */
    suspend fun fetchAndBurnMessages(mailboxHash: String): Result<List<EncryptedMessageRecord>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverBaseUrl/api/v1/inbox/$mailboxHash")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IOException("Fetch error: ${response.code}"))
                    }
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val msgsArray: JSONArray = json.optJSONArray("messages") ?: JSONArray()
                    val list = mutableListOf<EncryptedMessageRecord>()

                    for (i in 0 until msgsArray.length()) {
                        val obj = msgsArray.getJSONObject(i)
                        list.add(
                            EncryptedMessageRecord(
                                id = obj.getString("id"),
                                ciphertext = obj.getString("ciphertext"),
                                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                    }
                    Result.success(list)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Connect to live WebSocket push feed for zero-latency messaging.
     * Automatically reconnects if Tor circuit drops or network switches.
     */
    fun subscribeLive(
        mailboxHash: String,
        onMessageReceived: (EncryptedMessageRecord) -> Unit,
        onStatusChanged: (Boolean) -> Unit
    ) {
        this.isSubscribed = true
        this.activeMailboxHash = mailboxHash
        this.messageListener = onMessageReceived
        this.statusListener = onStatusChanged

        startWebSocketConnection()
    }

    private fun startWebSocketConnection() {
        disconnectWebSocket()

        val mailbox = activeMailboxHash ?: return
        val wsUrl = try {
            var url = serverBaseUrl.trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            url.replace("http://", "ws://").replace("https://", "wss://") + "/api/v1/live"
        } catch (e: Exception) {
            statusListener?.invoke(false)
            return
        }

        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: Exception) {
            statusListener?.invoke(false)
            return
        }

        currentWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectJob?.cancel()
                statusListener?.invoke(true)

                // Subscribe to blind mailbox
                val sub = JSONObject().apply {
                    put("type", "SUBSCRIBE")
                    put("mailboxHash", mailbox)
                }
                webSocket.send(sub.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "MESSAGE") {
                        val msgObj = json.getJSONObject("message")
                        val record = EncryptedMessageRecord(
                            id = msgObj.getString("id"),
                            ciphertext = msgObj.getString("ciphertext"),
                            createdAt = msgObj.optLong("createdAt", System.currentTimeMillis())
                        )
                        messageListener?.invoke(record)
                    }
                } catch (e: Exception) {
                    // Ignore malformed push payloads
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                statusListener?.invoke(false)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                statusListener?.invoke(false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isSubscribed) return
        reconnectJob?.cancel()
        reconnectJob = clientScope.launch {
            delay(4000) // Wait 4 seconds for Tor circuit or network stabilization
            if (isSubscribed) {
                startWebSocketConnection()
            }
        }
    }

    fun disconnectWebSocket() {
        isSubscribed = false
        reconnectJob?.cancel()
        currentWebSocket?.close(1000, "Client disconnect")
        currentWebSocket = null
    }
}
