package com.ghostkey.app.net.share

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Semaphore

data class OfflineShareState(
    val isSharing: Boolean = false,
    val shareUrl: String? = null,
    val apkSizeBytes: Long = 0L,
    val downloadsServed: Int = 0,
    val error: String? = null
)

/**
 * Briar-style Offline APK Sharing: serves the already-installed APK directly from
 * [android.content.pm.ApplicationInfo.publicSourceDir] over a temporary local HTTP server
 * with zero extra APK payload bloat.
 */
class OfflineAppSharer(private val context: Context) {

    companion object {
        private const val TAG = "OfflineAppSharer"
        private const val PREFERRED_PORT = 8787
        private const val MAX_CONCURRENT_DOWNLOADS = 4
        private const val STREAM_BUFFER_BYTES = 32 * 1024
    }

    private val _state = MutableStateFlow(OfflineShareState())
    val state: StateFlow<OfflineShareState> = _state.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var acceptJob: Job? = null
    private val permits = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    fun start(preferredHostIp: String? = null): OfflineShareState {
        if (acceptJob?.isActive == true && serverSocket?.isClosed == false) {
            return _state.value
        }
        stop()

        val apkFile = resolveInstalledApk()
        if (apkFile == null || !apkFile.exists() || !apkFile.canRead()) {
            val errState = OfflineShareState(isSharing = false, error = "Installed APK unavailable")
            _state.value = errState
            return errState
        }

        val socket = runCatching {
            ServerSocket(PREFERRED_PORT).apply { reuseAddress = true }
        }.getOrElse {
            runCatching { ServerSocket(0).apply { reuseAddress = true } }.getOrNull()
        }

        if (socket == null) {
            val errState = OfflineShareState(isSharing = false, error = "Could not open local HTTP port")
            _state.value = errState
            return errState
        }

        val hostIp = preferredHostIp?.takeIf { it.isNotBlank() } ?: resolveLocalIpv4()
        if (hostIp == null) {
            runCatching { socket.close() }
            val errState = OfflineShareState(isSharing = false, error = "Enable Wi-Fi, Hotspot, or Wi-Fi Direct first")
            _state.value = errState
            return errState
        }

        serverSocket = socket
        val shareUrl = "http://$hostIp:${socket.localPort}/ghostkey.apk"
        val shareScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = shareScope

        val activeState = OfflineShareState(
            isSharing = true,
            shareUrl = shareUrl,
            apkSizeBytes = apkFile.length(),
            downloadsServed = 0,
            error = null
        )
        _state.value = activeState

        acceptJob = shareScope.launch {
            Log.i(TAG, "Offline APK share server listening on $shareUrl (${apkFile.length()} bytes)")
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    handleClient(shareScope, client, apkFile)
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }
        return activeState
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        _state.value = OfflineShareState()
    }

    private fun resolveInstalledApk(): File? {
        val info = context.applicationInfo ?: return null
        val path = info.publicSourceDir ?: info.sourceDir ?: return null
        return File(path)
    }

    private fun handleClient(shareScope: CoroutineScope, client: Socket, apkFile: File) {
        if (!permits.tryAcquire()) {
            runCatching { client.close() }
            return
        }
        shareScope.launch {
            try {
                client.use { s ->
                    s.soTimeout = 15_000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val requestLine = reader.readLine()?.trim() ?: return@use
                    // Consume remaining headers up to blank line (bounded)
                    var lines = 0
                    while (lines++ < 64) {
                        val hdr = reader.readLine() ?: break
                        if (hdr.isEmpty()) break
                    }

                    val parts = requestLine.split(" ")
                    val method = parts.getOrNull(0)?.uppercase() ?: ""
                    val path = parts.getOrNull(1)?.substringBefore("?") ?: "/"
                    val out = s.getOutputStream()

                    if (method != "GET") {
                        writeTextResponse(out, 405, "Method Not Allowed", "text/plain", "Method Not Allowed")
                        return@use
                    }

                    when (path) {
                        "/", "/index.html" -> {
                            val sizeMb = String.format("%.2f MB", apkFile.length() / (1024.0 * 1024.0))
                            val html = """
                                <!doctype html>
                                <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                                <title>GhostKey Offline Share</title>
                                <style>body{font-family:sans-serif;background:#161513;color:#f4efe6;text-align:center;padding:32px}
                                a.btn{display:inline-block;margin-top:20px;padding:14px 28px;background:#d97757;color:#161513;
                                text-decoration:none;font-weight:bold;border-radius:10px}</style></head>
                                <body><h2>GhostKey Offline Install</h2><p>Package size: $sizeMb</p>
                                <a class="btn" href="/ghostkey.apk">Download GhostKey APK</a></body></html>
                            """.trimIndent()
                            writeTextResponse(out, 200, "OK", "text/html; charset=utf-8", html)
                        }
                        "/ghostkey.apk" -> {
                            streamApk(out, apkFile)
                            _state.value = _state.value.copy(downloadsServed = _state.value.downloadsServed + 1)
                        }
                        else -> writeTextResponse(out, 404, "Not Found", "text/plain", "Not Found")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Offline share request error: ${e.message}")
            } finally {
                permits.release()
            }
        }
    }

    private fun writeTextResponse(out: OutputStream, code: Int, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun streamApk(out: OutputStream, apkFile: File) {
        val totalLen = apkFile.length()
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/vnd.android.package-archive\r\n" +
            "Content-Disposition: attachment; filename=\"GhostKey.apk\"\r\n" +
            "Content-Length: $totalLen\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        BufferedInputStream(FileInputStream(apkFile), STREAM_BUFFER_BYTES).use { input ->
            val buf = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                out.write(buf, 0, read)
            }
        }
        out.flush()
    }

    private fun resolveLocalIpv4(): String? {
        return runCatching {
            val candidates = mutableListOf<Pair<Int, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (nif.isLoopback || !nif.isUp || nif.isVirtual) continue
                val name = nif.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") ||
                    name.contains("vpn") || name.startsWith("rmnet") || name.startsWith("ccmni")
                ) continue
                val priority = when {
                    name.startsWith("p2p") -> 1
                    name.matches(Regex("^(ap|swlan|wlan)[0-9].*")) -> 2
                    else -> 3
                }
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        addr.hostAddress?.let { ip ->
                            val boost = if (ip.startsWith("192.168.49.") || ip.startsWith("192.168.43.")) 0 else priority
                            candidates.add(boost to ip)
                        }
                    }
                }
            }
            candidates.minByOrNull { it.first }?.second
        }.getOrNull()
    }
}
