package com.ghostkey.app.crypto

import com.ghostkey.app.util.GhostKeyUtils
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
        senderAlias: String? = null,
        ttlSeconds: Long? = null
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
        if (ttlSeconds != null && ttlSeconds > 0) obj.put("ttl", ttlSeconds)
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
        senderAlias: String? = null,
        ttlSeconds: Long? = null
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
        if (ttlSeconds != null && ttlSeconds > 0) obj.put("ttl", ttlSeconds)
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

    private fun optSafeString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val s = obj.optString(key, "").trim()
        return if (s.isEmpty() || s.equals("null", ignoreCase = true)) null else s
    }

    /** Returns null if the bytes are not a valid v2 message. */
    fun parse(bytes: ByteArray): WireMessage? {
        return try {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            if (obj.optInt("v", -1) != VERSION) return null
            val ts = if (obj.has("ts") && !obj.isNull("ts")) obj.optLong("ts") else null
            val nonce = optSafeString(obj, "nonce")
            val senderAlias = optSafeString(obj, "senderAlias")
            val groupId = optSafeString(obj, "groupId")
            val rawTtl = if (obj.has("ttl") && !obj.isNull("ttl")) obj.optLong("ttl", 0L) else null
            val ttlSeconds = if (rawTtl != null && rawTtl > 0) Math.min(rawTtl, 30L * 86400L) else null

            when (val type = obj.optString("type")) {
                WireMessage.TYPE_TEXT -> {
                    if (!obj.has("text") || obj.isNull("text")) return null
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
                    if (!obj.has("data") || obj.isNull("data")) return null
                    val rawFilename = optSafeString(obj, "filename") ?: "file"
                    val safeFilename = rawFilename.replace("/", "_").replace("\\", "_").replace("..", "_")
                    WireMessage(
                        type = type,
                        text = null,
                        mimeType = optSafeString(obj, "mimeType") ?: "application/octet-stream",
                        filename = safeFilename,
                        data = obj.getString("data"),
                        timestamp = ts,
                        nonce = nonce,
                        senderAlias = senderAlias,
                        groupId = groupId,
                        ttlSeconds = ttlSeconds
                    )
                }
                WireMessage.TYPE_GROUP_INVITE -> {
                    val gDrop = optSafeString(obj, "groupDropId")?.lowercase()
                    val gToken = optSafeString(obj, "groupReadToken")?.lowercase()
                    val sKey = optSafeString(obj, "sharedKey")
                    // Cryptographically verify group invite tokens to prevent drop squatting
                    if (gDrop == null || gToken == null || sKey == null ||
                        !StandalonePgpEngine.isValidHex32(gDrop) ||
                        !StandalonePgpEngine.isValidHex32(gToken) ||
                        StandalonePgpEngine.deriveDropId(gToken) != gDrop ||
                        sKey.length != 64) {
                        return null
                    }
                    WireMessage(
                        type = type,
                        text = null,
                        mimeType = null,
                        filename = null,
                        data = null,
                        timestamp = ts,
                        nonce = nonce,
                        senderAlias = senderAlias,
                        groupId = optSafeString(obj, "groupId"),
                        groupName = optSafeString(obj, "groupName"),
                        groupDropId = gDrop,
                        groupReadToken = gToken,
                        sharedKey = sKey,
                        ttlSeconds = ttlSeconds
                    )
                }
                WireMessage.TYPE_INTRODUCTION -> {
                    val introDrop = optSafeString(obj, "introDropId")?.lowercase()
                    val introKey = optSafeString(obj, "introArmoredKey")
                    val rawAlias = optSafeString(obj, "introAlias") ?: "Peer"
                    val cleanIntroAlias = GhostKeyUtils.sanitizeAlias(rawAlias)
                    // Validate peer introduction Drop-ID and PGP public key envelope
                    if (introDrop == null || !StandalonePgpEngine.isValidHex32(introDrop) ||
                        introKey == null || !introKey.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----")) {
                        return null
                    }
                    WireMessage(
                        type = type,
                        text = null,
                        mimeType = null,
                        filename = null,
                        data = null,
                        timestamp = ts,
                        nonce = nonce,
                        senderAlias = senderAlias,
                        introAlias = cleanIntroAlias,
                        introDropId = introDrop,
                        introArmoredKey = introKey,
                        introSafetyCode = optSafeString(obj, "introSafetyCode"),
                        ttlSeconds = ttlSeconds
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
