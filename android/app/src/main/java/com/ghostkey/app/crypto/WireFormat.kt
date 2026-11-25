package com.ghostkey.app.crypto

import com.ghostkey.app.util.GhostKeyUtils
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/** A decoded v2 plaintext message (travels inside the OpenPGP or Group AES-GCM envelope). */
class WireMessage(
    val type: String,
    val text: String? = null,
    val mimeType: String? = null,
    val filename: String? = null,
    val data: String? = null,
    val timestamp: Long? = null,
    val nonce: String? = null,
    val msgId: String? = null,
    val replyTo: String? = null,
    val senderAlias: String? = null,
    val groupId: String? = null,
    val groupName: String? = null,
    val groupDropId: String? = null,
    val groupReadToken: String? = null,
    val sharedKey: String? = null,
    val ttlSeconds: Long? = null,
    val introAlias: String? = null,
    val introDropId: String? = null,
    val introArmoredKey: String? = null,
    val introSafetyCode: String? = null,
    val profileAlias: String? = null,
    val avatarHash: String? = null,
    val avatarBase64: String? = null,
    val groupControlAction: String? = null,
    val groupControlTargetDropId: String? = null,
    val groupControlNewKey: String? = null
) {
    val isMedia: Boolean get() = type == TYPE_MEDIA
    val isGroupInvite: Boolean get() = type == TYPE_GROUP_INVITE
    val isIntroduction: Boolean get() = type == TYPE_INTRODUCTION
    val isProfileUpdate: Boolean get() = type == TYPE_PROFILE_UPDATE
    val isGroupControl: Boolean get() = type == TYPE_GROUP_CONTROL

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_MEDIA = "media"
        const val TYPE_GROUP_INVITE = "group_invite"
        const val TYPE_INTRODUCTION = "introduction"
        const val TYPE_PROFILE_UPDATE = "profile_update"
        const val TYPE_GROUP_CONTROL = "group_control"

        const val GROUP_ACTION_LEAVE = "leave"
        const val GROUP_ACTION_KEY_ROTATE = "key_rotate"
    }
}

/**
 * Unified v2 plaintext JSON envelope, compatible across Android, Web, Tor, LAN/Wi-Fi, and BLE Mesh.
 */
object WireFormat {

    const val VERSION = 2
    private const val SMALL_BUCKET = 512
    private const val LARGE_BUCKET = 16 * 1024
    private const val LARGE_THRESHOLD = 64 * 1024
    private const val MIN_PAD_CHARS = 12
    private const val MAX_AVATAR_DECODED_BYTES = 64 * 1024

    private val random = SecureRandom()

    fun randomHex16(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return GhostKeyUtils.bytesToHex(b)
    }

    private fun buildEnvelope(
        type: String,
        senderAlias: String? = null,
        groupId: String? = null,
        ttlSeconds: Long? = null,
        replyTo: String? = null,
        msgId: String? = null,
        populate: JSONObject.() -> Unit
    ): ByteArray {
        val obj = JSONObject()
        obj.put("v", VERSION)
        obj.put("type", type)
        obj.put("msgId", msgId?.takeIf { it.isNotBlank() } ?: randomHex16())
        obj.put("ts", System.currentTimeMillis())
        obj.put("nonce", randomHex16())
        if (!senderAlias.isNullOrBlank()) obj.put("senderAlias", senderAlias.trim())
        if (!groupId.isNullOrBlank()) obj.put("groupId", groupId.trim())
        if (ttlSeconds != null && ttlSeconds > 0) obj.put("ttl", ttlSeconds)
        if (!replyTo.isNullOrBlank()) obj.put("replyTo", replyTo.trim())
        obj.populate()
        return withPad(obj)
    }

    fun buildText(
        text: String,
        senderAlias: String? = null,
        groupId: String? = null,
        ttlSeconds: Long? = null,
        replyTo: String? = null,
        msgId: String? = null
    ): ByteArray = buildEnvelope(WireMessage.TYPE_TEXT, senderAlias, groupId, ttlSeconds, replyTo, msgId) {
        put("text", text)
    }

    fun buildMedia(
        mimeType: String,
        filename: String,
        bytes: ByteArray,
        senderAlias: String? = null,
        groupId: String? = null,
        ttlSeconds: Long? = null,
        replyTo: String? = null,
        msgId: String? = null
    ): ByteArray = buildEnvelope(WireMessage.TYPE_MEDIA, senderAlias, groupId, ttlSeconds, replyTo, msgId) {
        put("mimeType", mimeType)
        put("filename", filename)
        put("data", Base64.getEncoder().encodeToString(bytes))
    }

    fun buildGroupInvite(
        groupId: String,
        groupName: String,
        groupDropId: String,
        groupReadToken: String,
        sharedKey: String,
        senderAlias: String? = null,
        ttlSeconds: Long? = null,
        msgId: String? = null
    ): ByteArray = buildEnvelope(WireMessage.TYPE_GROUP_INVITE, senderAlias, groupId, ttlSeconds, msgId = msgId) {
        put("groupName", groupName.trim())
        put("groupDropId", groupDropId.trim())
        put("groupReadToken", groupReadToken.trim())
        put("sharedKey", sharedKey.trim())
    }

    fun buildIntroduction(
        introAlias: String,
        introDropId: String,
        introArmoredKey: String,
        introSafetyCode: String? = null,
        senderAlias: String? = null,
        ttlSeconds: Long? = null,
        msgId: String? = null
    ): ByteArray = buildEnvelope(WireMessage.TYPE_INTRODUCTION, senderAlias, null, ttlSeconds, msgId = msgId) {
        put("introAlias", introAlias.trim())
        put("introDropId", introDropId.trim())
        put("introArmoredKey", introArmoredKey.trim())
        if (!introSafetyCode.isNullOrBlank()) put("introSafetyCode", introSafetyCode.trim())
    }

    fun buildProfileUpdate(
        profileAlias: String? = null,
        avatarBase64: String? = null,
        avatarHash: String? = null,
        senderAlias: String? = null,
        msgId: String? = null
    ): ByteArray = buildEnvelope(WireMessage.TYPE_PROFILE_UPDATE, senderAlias, msgId = msgId) {
        if (!profileAlias.isNullOrBlank()) put("profileAlias", profileAlias.trim())
        if (!avatarHash.isNullOrBlank()) put("avatarHash", avatarHash.trim())
        if (!avatarBase64.isNullOrBlank()) put("avatarData", avatarBase64.trim())
    }

    fun buildGroupControl(
        groupId: String,
        action: String,
        targetDropId: String? = null,
        newSharedKey: String? = null,
        senderAlias: String? = null,
        msgId: String? = null
    ): ByteArray = buildEnvelope(WireMessage.TYPE_GROUP_CONTROL, senderAlias, groupId, msgId = msgId) {
        put("action", action.trim())
        if (!targetDropId.isNullOrBlank()) put("targetDropId", targetDropId.trim())
        if (!newSharedKey.isNullOrBlank()) put("newSharedKey", newSharedKey.trim())
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

    private fun isValidAes256Key(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        if (key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return true
        return runCatching { Base64.getDecoder().decode(key).size == 32 }.getOrDefault(false)
    }

    /** Returns null if the bytes are not a valid v2 message. */
    fun parse(bytes: ByteArray): WireMessage? {
        return try {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            if (obj.optInt("v", -1) != VERSION) return null
            val ts = if (obj.has("ts") && !obj.isNull("ts")) obj.optLong("ts") else null
            val nonce = optSafeString(obj, "nonce")
            val msgId = optSafeString(obj, "msgId") ?: nonce
            val replyTo = optSafeString(obj, "replyTo")
            val senderAlias = optSafeString(obj, "senderAlias")
            val groupId = optSafeString(obj, "groupId")
            val rawTtl = if (obj.has("ttl") && !obj.isNull("ttl")) obj.optLong("ttl", 0L) else null
            val ttlSeconds = if (rawTtl != null && rawTtl > 0) minOf(rawTtl, 30L * 86400L) else null

            when (val type = obj.optString("type")) {
                WireMessage.TYPE_TEXT -> {
                    if (!obj.has("text") || obj.isNull("text")) return null
                    WireMessage(
                        type = type,
                        text = obj.getString("text"),
                        timestamp = ts,
                        nonce = nonce,
                        msgId = msgId,
                        replyTo = replyTo,
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
                        mimeType = optSafeString(obj, "mimeType") ?: "application/octet-stream",
                        filename = safeFilename,
                        data = obj.getString("data"),
                        timestamp = ts,
                        nonce = nonce,
                        msgId = msgId,
                        replyTo = replyTo,
                        senderAlias = senderAlias,
                        groupId = groupId,
                        ttlSeconds = ttlSeconds
                    )
                }
                WireMessage.TYPE_GROUP_INVITE -> {
                    val gDrop = optSafeString(obj, "groupDropId")?.lowercase()
                    val gToken = optSafeString(obj, "groupReadToken")?.lowercase()
                    val sKey = optSafeString(obj, "sharedKey")
                    if (gDrop == null || gToken == null || sKey == null ||
                        !StandalonePgpEngine.isValidHex32(gDrop) ||
                        !StandalonePgpEngine.isValidHex32(gToken) ||
                        StandalonePgpEngine.deriveDropId(gToken) != gDrop ||
                        !isValidAes256Key(sKey)
                    ) {
                        return null
                    }
                    WireMessage(
                        type = type,
                        timestamp = ts,
                        nonce = nonce,
                        msgId = msgId,
                        senderAlias = senderAlias,
                        groupId = groupId,
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
                    if (introDrop == null || !StandalonePgpEngine.isValidHex32(introDrop) ||
                        introKey == null || !introKey.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----")
                    ) {
                        return null
                    }
                    WireMessage(
                        type = type,
                        timestamp = ts,
                        nonce = nonce,
                        msgId = msgId,
                        senderAlias = senderAlias,
                        introAlias = cleanIntroAlias,
                        introDropId = introDrop,
                        introArmoredKey = introKey,
                        introSafetyCode = optSafeString(obj, "introSafetyCode"),
                        ttlSeconds = ttlSeconds
                    )
                }
                WireMessage.TYPE_PROFILE_UPDATE -> {
                    val rawAlias = optSafeString(obj, "profileAlias")
                    val cleanAlias = rawAlias?.let { GhostKeyUtils.sanitizeAlias(it) }
                    val avatarHash = optSafeString(obj, "avatarHash")?.lowercase()
                    val avatarData = optSafeString(obj, "avatarData")
                    if (avatarData != null) {
                        val decoded = runCatching { Base64.getDecoder().decode(avatarData) }.getOrNull() ?: return null
                        if (decoded.size > MAX_AVATAR_DECODED_BYTES) return null
                    }
                    WireMessage(
                        type = type,
                        timestamp = ts,
                        nonce = nonce,
                        msgId = msgId,
                        senderAlias = senderAlias,
                        profileAlias = cleanAlias,
                        avatarHash = avatarHash,
                        avatarBase64 = avatarData
                    )
                }
                WireMessage.TYPE_GROUP_CONTROL -> {
                    val action = optSafeString(obj, "action") ?: return null
                    if (groupId.isNullOrBlank()) return null
                    val newKey = optSafeString(obj, "newSharedKey")
                    if (action == WireMessage.GROUP_ACTION_KEY_ROTATE && !isValidAes256Key(newKey)) {
                        return null
                    }
                    WireMessage(
                        type = type,
                        timestamp = ts,
                        nonce = nonce,
                        msgId = msgId,
                        senderAlias = senderAlias,
                        groupId = groupId,
                        groupControlAction = action,
                        groupControlTargetDropId = optSafeString(obj, "targetDropId")?.lowercase(),
                        groupControlNewKey = newKey
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decodeMediaData(message: WireMessage): ByteArray =
        Base64.getDecoder().decode(message.data ?: "")
}
