package com.ghostkey.app.crypto

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** A decoded v2 plaintext message (the thing that travels inside the OpenPGP envelope). */
class WireMessage(
    val type: String,                   // "text" | "media" | "group_invite"
    val text: String?,                  // for type == "text"
    val mimeType: String?,              // for type == "media"
    val filename: String?,              // for type == "media"
    val data: String?,                  // for type == "media": standard base64 (no data: prefix)
    val timestamp: Long? = null,
    val nonce: String? = null,
    val senderAlias: String? = null,    // sender pseudonym (e.g. "Shadow")
    val groupId: String? = null,        // target group ID if group message
    val groupName: String? = null,      // group name for invites
    val groupDropId: String? = null,    // group mailbox for invites
    val groupReadToken: String? = null, // group mailbox read token for invites
    val sharedKey: String? = null,      // group AES-256 key for invites
    val ttlSeconds: Long? = null,       // disappearing messages timer in seconds
    val introAlias: String? = null,     // Peer contact introduction: target alias
    val introDropId: String? = null,    // Peer contact introduction: target dropId
    val introArmoredKey: String? = null,// Peer contact introduction: target public key
    val introSafetyCode: String? = null // Peer contact introduction: target SAS safety code
) {
    val isMedia: Boolean get() = type == TYPE_MEDIA
    val isGroupInvite: Boolean get() = type == TYPE_GROUP_INVITE
    val isIntroduction: Boolean get() = type == TYPE_INTRODUCTION

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_MEDIA = "media"
        const val TYPE_GROUP_INVITE = "group_invite"
        const val TYPE_INTRODUCTION = "introduction"
    }
}

/**
 * Unified v2 plaintext JSON, identical on Android and Web:
 *   { "v": 2, "type": "text" | "media" | "group_invite", "text": "...", "mimeType": "...",
 *     "filename": "...", "data": "<base64>", "ts": 1234567890, "nonce": "...",
 *     "senderAlias": "...", "groupId": "...", "pad": "<random base64url>" }
 */
object WireFormat {

    const val VERSION = 2
    private const val SMALL_BUCKET = 512
    private const val LARGE_BUCKET = 16 * 1024
    private const val LARGE_THRESHOLD = 64 * 1024
    private const val MIN_PAD_CHARS = 12

    private val random = SecureRandom()

    private fun randomHex16(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun buildText(
        text: String,
        senderAlias: String? = null,
        groupId: String? = null,
        ttlSeconds: Long? = null
    ): ByteArray {
        val obj = JSONObject()
        obj.put("v", VERSION)
        obj.put("type", WireMessage.TYPE_TEXT)
        obj.put("text", text)
        obj.put("ts", System.currentTimeMillis())
        obj.put("nonce", randomHex16())
        if (!senderAlias.isNullOrBlank()) obj.put("senderAlias", senderAlias.trim())
        if (!groupId.isNullOrBlank()) obj.put("groupId", groupId.trim())
        if (ttlSeconds != null && ttlSeconds > 0) obj.put("ttl", ttlSeconds)
        return withPad(obj)
    }

    fun buildMedia(
        mimeType: String,
        filename: String,
        bytes: ByteArray,
        senderAlias: String? = null,
        groupId: String? = null,
        ttlSeconds: Long? = null
    ): ByteArray {
        val obj = JSONObject()
        obj.put("v", VERSION)
        obj.put("type", WireMessage.TYPE_MEDIA)
        obj.put("mimeType", mimeType)
        obj.put("filename", filename)
        obj.put("data", Base64.getEncoder().encodeToString(bytes))
        obj.put("ts", System.currentTimeMillis())
        obj.put("nonce", randomHex16())
        if (!senderAlias.isNullOrBlank()) obj.put("senderAlias", senderAlias.trim())
        if (!groupId.isNullOrBlank()) obj.put("groupId", groupId.trim())
        if (ttlSeconds != null && ttlSeconds > 0) obj.put("ttl", ttlSeconds)
        return withPad(obj)
    }

    fun buildGroupInvite(
        groupId: String,
        groupName: String,
        groupDropId: String,
        groupReadToken: String,
        sharedKey: String,
        senderAlias: String? = null
    ): ByteArray {
        val obj = JSONObject()
        obj.put("v", VERSION)
        obj.put("type", WireMessage.TYPE_GROUP_INVITE)
        obj.put("groupId", groupId.trim())
        obj.put("groupName", groupName.trim())
        obj.put("groupDropId", groupDropId.trim())
        obj.put("groupReadToken", groupReadToken.trim())
        obj.put("sharedKey", sharedKey.trim())
        obj.put("ts", System.currentTimeMillis())
        obj.put("nonce", randomHex16())
        if (!senderAlias.isNullOrBlank()) obj.put("senderAlias", senderAlias.trim())
        return withPad(obj)
    }

    /**
     * Mutual Contact Introduction Protocol:
     * User introduces contact B to contact C by sending an introduction payload.
     */
    fun buildIntroduction(
        introAlias: String,
        introDropId: String,
        introArmoredKey: String,
        introSafetyCode: String? = null,
        senderAlias: String? = null
    ): ByteArray {
        val obj = JSONObject()
        obj.put("v", VERSION)
        obj.put("type", WireMessage.TYPE_INTRODUCTION)
        obj.put("introAlias", introAlias.trim())
        obj.put("introDropId", introDropId.trim())
        obj.put("introArmoredKey", introArmoredKey.trim())
        if (!introSafetyCode.isNullOrBlank()) obj.put("introSafetyCode", introSafetyCode.trim())
        obj.put("ts", System.currentTimeMillis())
        obj.put("nonce", randomHex16())
        if (!senderAlias.isNullOrBlank()) obj.put("senderAlias", senderAlias.trim())
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
            val ts = if (obj.has("ts")) obj.getLong("ts") else null
            val nonce = obj.optString("nonce", null)
            val senderAlias = obj.optString("senderAlias", null)?.ifBlank { null }
            val groupId = obj.optString("groupId", null)?.ifBlank { null }
            val ttlSeconds = if (obj.has("ttl")) obj.getLong("ttl") else null

            when (val type = obj.optString("type")) {
                WireMessage.TYPE_TEXT -> {
                    if (!obj.has("text")) return null
                    WireMessage(
                        type = type,
                        text = obj.getString("text"),
                        mimeType = null,
                        filename = null,
                        data = null,
                        timestamp = ts,
                        nonce = nonce,
                        senderAlias = senderAlias,
                        groupId = groupId,
                        ttlSeconds = ttlSeconds
                    )
                }
                WireMessage.TYPE_MEDIA -> {
                    if (!obj.has("data")) return null
                    WireMessage(
                        type = type,
                        text = null,
                        mimeType = obj.optString("mimeType", "application/octet-stream"),
                        filename = obj.optString("filename", "file"),
                        data = obj.getString("data"),
                        timestamp = ts,
                        nonce = nonce,
                        senderAlias = senderAlias,
                        groupId = groupId,
                        ttlSeconds = ttlSeconds
                    )
                }
                WireMessage.TYPE_GROUP_INVITE -> {
                    WireMessage(
                        type = type,
                        text = null,
                        mimeType = null,
                        filename = null,
                        data = null,
                        timestamp = ts,
                        nonce = nonce,
                        senderAlias = senderAlias,
                        groupId = obj.optString("groupId", null),
                        groupName = obj.optString("groupName", null),
                        groupDropId = obj.optString("groupDropId", null),
                        groupReadToken = obj.optString("groupReadToken", null),
                        sharedKey = obj.optString("sharedKey", null)
                    )
                }
                WireMessage.TYPE_INTRODUCTION -> {
                    WireMessage(
                        type = type,
                        text = null,
                        mimeType = null,
                        filename = null,
                        data = null,
                        timestamp = ts,
                        nonce = nonce,
                        senderAlias = senderAlias,
                        introAlias = obj.optString("introAlias", null),
                        introDropId = obj.optString("introDropId", null),
                        introArmoredKey = obj.optString("introArmoredKey", null),
                        introSafetyCode = obj.optString("introSafetyCode", null)
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
