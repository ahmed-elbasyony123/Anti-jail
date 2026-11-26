package com.ghostkey.app.crypto

import com.ghostkey.app.data.Contact
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zero-knowledge 5-minute Rendezvous invite codes & links (`XXXXX-XXXXX`, `#pair=`, `ghostkey://pair?c=`).
 *
 * Byte-for-byte compatible with the Web Console (`ghostkey-core.mjs`):
 * - Lookup ID (`slotId`) = SHA-256("gk-rv-id-v1:" + canonicalCode)
 * - Encryption key = PBKDF2-HMAC-SHA256("gk-rv-enc-v1:" + canonicalCode, salt, 100_000, 256 bits)
 * - Cipher = AES-256-GCM with 12-byte IV and AAD = "ghostkey-rendezvous-v1"
 */
object RendezvousPairing {

    const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    const val CODE_LEN = 10
    private const val PBKDF2_ITERATIONS = 100_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val AAD = "ghostkey-rendezvous-v1".toByteArray(Charsets.UTF_8)
    private val LINK_PARAM_REGEX = Regex("""(?:^|[#&?])(?:pair|c)=([^&#\s]+)""", RegexOption.IGNORE_CASE)
    private val random = SecureRandom()

    data class SealedRendezvous(
        val slotId: String,
        val sealedBlob: String
    )

    fun generateCode(): String {
        val bytes = ByteArray(CODE_LEN)
        random.nextBytes(bytes)
        val raw = buildString(CODE_LEN) {
            for (b in bytes) append(ALPHABET[(b.toInt() and 0xFF) and 31])
        }
        return formatCode(raw)
    }

    fun normalizeCode(rawInput: String): String {
        val cleaned = rawInput.uppercase().replace(Regex("[^0-9A-Z]"), "")
        require(cleaned.length == CODE_LEN) { "Invite code must be $CODE_LEN characters" }
        for (ch in cleaned) {
            require(ch in ALPHABET) { "Invalid character '$ch' in invite code" }
        }
        return cleaned
    }

    fun formatCode(canonicalOrFormatted: String): String {
        val c = normalizeCode(canonicalOrFormatted)
        return "${c.substring(0, 5)}-${c.substring(5)}"
    }

    fun extractCode(rawInput: String): String {
        val text = rawInput.trim()
        require(text.isNotEmpty()) { "Enter a pairing code or invite link" }
        val match = LINK_PARAM_REGEX.find(text)
        if (match != null) {
            val decoded = runCatching { java.net.URLDecoder.decode(match.groupValues[1], "UTF-8") }
                .getOrDefault(match.groupValues[1])
            return normalizeCode(decoded)
        }
        val hashIdx = text.indexOf('#')
        if (hashIdx >= 0 && hashIdx + 1 < text.length) {
            val frag = text.substring(hashIdx + 1).removePrefix("pair=").removePrefix("PAIR=")
            val decoded = runCatching { java.net.URLDecoder.decode(frag, "UTF-8") }.getOrDefault(frag)
            if (decoded.isNotBlank()) return normalizeCode(decoded)
        }
        return normalizeCode(text)
    }

    fun buildWebInviteLink(relayBaseUrl: String, code: String): String {
        val formatted = formatCode(code)
        val base = relayBaseUrl.trim().trimEnd('/')
        return if (base.isNotEmpty()) "$base/#pair=$formatted" else buildAppInviteLink(formatted)
    }

    fun buildAppInviteLink(code: String): String = "ghostkey://pair?c=${formatCode(code)}"

    fun deriveSlotId(codeInput: String): String {
        val canonical = normalizeCode(codeInput)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("gk-rv-id-v1:$canonical".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sealContactCard(codeInput: String, contactCardJson: String): SealedRendezvous {
        val canonical = normalizeCode(codeInput)
        val slotId = deriveSlotId(canonical)
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val key = deriveAesKey(canonical, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val ctPlusTag = cipher.doFinal(contactCardJson.toByteArray(Charsets.UTF_8))
            val wrapper = JSONObject()
                .put("v", 1)
                .put("salt", Base64.getEncoder().encodeToString(salt))
                .put("iv", Base64.getEncoder().encodeToString(iv))
                .put("ct", Base64.getEncoder().encodeToString(ctPlusTag))
            SealedRendezvous(slotId = slotId, sealedBlob = wrapper.toString())
        } finally {
            key.encoded.fill(0)
        }
    }

    fun openContactCard(codeInput: String, sealedBlobJson: String): ContactCard {
        val canonical = normalizeCode(codeInput)
        val wrapper = runCatching { JSONObject(sealedBlobJson.trim()) }
            .getOrElse { throw IllegalArgumentException("Malformed rendezvous payload", it) }
        require(wrapper.optInt("v", -1) == 1) { "Unsupported rendezvous payload version" }
        val salt = Base64.getDecoder().decode(wrapper.getString("salt"))
        val iv = Base64.getDecoder().decode(wrapper.getString("iv"))
        val ct = Base64.getDecoder().decode(wrapper.getString("ct"))
        val key = deriveAesKey(canonical, salt)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(ct)
        } finally {
            key.encoded.fill(0)
        }
        return try {
            InPersonPairing.parseContactCard(String(plain, Charsets.UTF_8))
                ?: throw IllegalArgumentException("Invalid or unverified Contact Card")
        } finally {
            plain.fill(0)
        }
    }

    private fun deriveAesKey(canonicalCode: String, salt: ByteArray): SecretKeySpec {
        val password = "gk-rv-enc-v1:$canonicalCode".toCharArray()
        return try {
            val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, 256)
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(raw, "AES").also { raw.fill(0) }
        } finally {
            password.fill('\u0000')
        }
    }
}
