package com.ghostkey.app.util

import android.content.ClipboardManager
import android.content.Context
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object GhostKeyUtils {
    private val ALIAS_CONTROL_CHARS = Regex("[\\r\\n\\t\\u202A-\\u202E\\u200E\\u200F<>]")
    private val CLOCK_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

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

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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
