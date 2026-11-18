package com.ghostkey.app.crypto

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** A decoded v2 plaintext message (the thing that travels inside the OpenPGP envelope). */
class WireMessage(
    val type: String,           // "text" | "media"
    val text: String?,          // for type == "text"
    val mimeType: String?,      // for type == "media"
    val filename: String?,      // for type == "media"
    val data: String?           // for type == "media": standard base64 (no data: prefix)
) {
    val isMedia: Boolean get() = type == TYPE_MEDIA

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_MEDIA = "media"
    }
}

/**
 * Unified v2 plaintext JSON, identical on Android and Web:
 *   { "v": 2, "type": "text" | "media", "text": "...", "mimeType": "...", "filename": "...",
 *     "data": "<base64>", "pad": "<random base64url>" }
 *
 * `pad` is random filler that rounds the plaintext up to a size bucket so the exact message
 * length is not directly visible in the ciphertext size (it does not hide it completely).
 */
object WireFormat {

    const val VERSION = 2
    private const val SMALL_BUCKET = 512
    private const val LARGE_BUCKET = 16 * 1024
    private const val LARGE_THRESHOLD = 64 * 1024
    private const val MIN_PAD_CHARS = 12

    private val random = SecureRandom()

    fun buildText(text: String): ByteArray {
        val obj = JSONObject()
        obj.put("v", VERSION)
        obj.put("type", WireMessage.TYPE_TEXT)
        obj.put("text", text)
        return withPad(obj)
    }

    fun buildMedia(mimeType: String, filename: String, bytes: ByteArray): ByteArray {
        val obj = JSONObject()
        obj.put("v", VERSION)
        obj.put("type", WireMessage.TYPE_MEDIA)
        obj.put("mimeType", mimeType)
        obj.put("filename", filename)
        obj.put("data", Base64.getEncoder().encodeToString(bytes))
        return withPad(obj)
    }

    private fun withPad(obj: JSONObject): ByteArray {
        obj.put("pad", "")
        val baseLen = obj.toString().toByteArray(Charsets.UTF_8).size
        val bucket = if (baseLen < LARGE_THRESHOLD) SMALL_BUCKET else LARGE_BUCKET
        var target = ((baseLen + bucket - 1) / bucket) * bucket
        if (target - baseLen < MIN_PAD_CHARS) target += bucket
        val padChars = target - baseLen
        obj.put("pad", randomBase64Url(padChars))
        return obj.toString().toByteArray(Charsets.UTF_8)
    }

    private fun randomBase64Url(chars: Int): String {
        val raw = ByteArray((chars * 3) / 4 + 3)
        random.nextBytes(raw)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw).substring(0, chars)
    }

    /** Returns null if the bytes are not a valid v2 message. */
    fun parse(bytes: ByteArray): WireMessage? {
        return try {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            if (obj.optInt("v", -1) != VERSION) return null
            when (val type = obj.optString("type")) {
                WireMessage.TYPE_TEXT -> {
                    if (!obj.has("text")) return null
                    WireMessage(type, obj.getString("text"), null, null, null)
                }
                WireMessage.TYPE_MEDIA -> {
                    if (!obj.has("data")) return null
                    WireMessage(
                        type, null,
                        obj.optString("mimeType", "application/octet-stream"),
                        obj.optString("filename", "file"),
                        obj.getString("data")
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun decodeMediaData(message: WireMessage): ByteArray =
        Base64.getDecoder().decode(message.data ?: "")
}
