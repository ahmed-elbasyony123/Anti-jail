package com.ghostkey.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)

        if (useTorProxy) {
            val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(torSocksHost, torSocksPort))
            builder.proxy(socksProxy)
        }

        return builder.build()
    }

    fun updateConfig(baseUrl: String, useTor: Boolean, host: String = "127.0.0.1", port: Int = 9050) {
        this.serverBaseUrl = baseUrl.trimEnd('/')
        this.useTorProxy = useTor
        this.torSocksHost = host
        this.torSocksPort = port
        this.httpClient = buildClient()
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
     */
    fun subscribeLive(
        mailboxHash: String,
        onMessageReceived: (EncryptedMessageRecord) -> Unit,
        onStatusChanged: (Boolean) -> Unit
    ) {
        disconnectWebSocket()

        val wsUrl = serverBaseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/api/v1/live"
        val request = Request.Builder().url(wsUrl).build()

        currentWebSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatusChanged(true)
                // Subscribe to blind mailbox
                val sub = JSONObject().apply {
                    put("type", "SUBSCRIBE")
                    put("mailboxHash", mailboxHash)
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
                        onMessageReceived(record)
                    }
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatusChanged(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatusChanged(false)
            }
        })
    }

    fun disconnectWebSocket() {
        currentWebSocket?.close(1000, "App closed")
        currentWebSocket = null
    }
}
