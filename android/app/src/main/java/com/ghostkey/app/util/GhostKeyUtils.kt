package com.ghostkey.app.util

import android.content.ClipboardManager
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.NetworkInterface
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object GhostKeyUtils {
    private val ALIAS_CONTROL_CHARS = Regex("[\\r\\n\\t\\u202A-\\u202E\\u200E\\u200F<>]")
    private val CLOCK_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())
    private val HEX_LOWER = "0123456789abcdef".toCharArray()
    private val HEX_UPPER = "0123456789ABCDEF".toCharArray()

    fun sanitizeAlias(raw: String?, maxLen: Int = 64): String =
        raw?.trim()?.replace(ALIAS_CONTROL_CHARS, "")?.take(maxLen)?.trim().orEmpty()

    fun sanitizeRelayUrl(url: String): String {
        var clean = url.trim().trimEnd('/')
        if (clean.isNotEmpty() && !clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = if (
                clean.contains(".onion", ignoreCase = true) ||
                clean.startsWith("127.0.0.1") ||
                clean.startsWith("localhost") ||
                clean.startsWith("10.0.2.2")
            ) {
                "http://$clean"
            } else {
                "https://$clean"
            }
        }
        return clean
    }

    fun formatDurationMmSs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d", totalSec / 60L, totalSec % 60L)
    }

    fun formatClockTime(epochMs: Long): String =
        CLOCK_FORMATTER.format(Instant.ofEpochMilli(epochMs))

    /** Allocation-light hex encoding avoiding per-byte String.format calls on hot paths. */
    fun bytesToHex(bytes: ByteArray, uppercase: Boolean = false): String {
        val alphabet = if (uppercase) HEX_UPPER else HEX_LOWER
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = alphabet[v ushr 4]
            out[i * 2 + 1] = alphabet[v and 0x0F]
        }
        return String(out)
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytesToHex(digest)
    }

    /** Reads at most [maxBytes] from [input]; returns null if the stream exceeds the cap. */
    fun readStreamLimited(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Lists active non-loopback local network interfaces, excluding VPNs, cellular, and virtual links
     * to prevent privacy leaks during local mesh discovery or offline APK sharing.
     */
    fun localNetworkInterfaces(includeP2p: Boolean = false): List<NetworkInterface> {
        val result = mutableListOf<NetworkInterface>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return result
        while (interfaces.hasMoreElements()) {
            val netIf = interfaces.nextElement()
            if (netIf.isLoopback || !netIf.isUp || netIf.isVirtual) continue
            val name = netIf.name.lowercase()
            if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") ||
                name.startsWith("dummy") || name.contains("vpn") ||
                name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("usbnet") ||
                (!includeP2p && name.startsWith("p2p"))
            ) {
                continue
            }
            result.add(netIf)
        }
        return result
    }

    fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while ((width / sample) > maxEdge || (height / sample) > maxEdge) sample *= 2
        return sample
    }

    fun readClipboardText(context: Context): String? {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        } catch (_: Exception) {
            null
        }
    }
}
