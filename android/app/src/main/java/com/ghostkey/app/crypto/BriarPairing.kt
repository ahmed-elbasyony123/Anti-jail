package com.ghostkey.app.crypto

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.security.MessageDigest

data class ContactCard(
    val alias: String,
    val dropId: String,
    val fingerprint: String,
    val publicKeyArmored: String
)

/**
 * Briar-style cryptographic contact pairing and verification suite.
 *
 * Provides:
 * 1. ZXing QR code generation and decoding in RAM.
 * 2. Deterministic Short Authentication String (SAS / Safety Code) calculation
 *    matching Briar & Signal's in-person verification standard.
 * 3. Secure contact card encoding/decoding.
 */
object BriarPairing {

    const val URI_SCHEME = "ghostkey://contact"

    /**
     * Generate an in-memory QR Code [Bitmap] for visual scanning.
     */
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Decodes a QR code from a given in-memory Android [Bitmap].
     */
    fun decodeQrFromBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(binaryBitmap)
            result.text
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Serializes contact details into a compact JSON string.
     */
    fun encodeContactCard(alias: String, dropId: String, fingerprint: String, publicKeyArmored: String): String {
        val obj = JSONObject()
        obj.put("v", 1)
        obj.put("type", "ghostkey_contact")
        obj.put("alias", alias)
        obj.put("dropId", dropId)
        obj.put("fp", fingerprint)
        obj.put("key", publicKeyArmored)
        return obj.toString()
    }

    /**
     * Parses a scanned or pasted string: supports JSON contact cards, URI formats,
     * or raw OpenPGP public key blocks.
     */
    fun parseContactCard(raw: String): ContactCard? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Format 1: JSON contact card
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val key = json.getString("key")
                val parsed = try { StandalonePgpEngine.parsePeerKey(key) } catch (_: Exception) { null }
                val dropId = json.optString("dropId", "").ifBlank {
                    parsed?.dropId ?: ""
                }
                val fp = json.optString("fp", "").ifBlank {
                    parsed?.fingerprint ?: ""
                }
                val alias = json.optString("alias", parsed?.alias ?: "Peer")
                return ContactCard(alias = alias, dropId = dropId, fingerprint = fp, publicKeyArmored = key)
            } catch (_: Exception) {}
        }

        // Format 2: Raw PGP Public Key Block
        if (trimmed.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----")) {
            return try {
                val info = StandalonePgpEngine.parsePeerKey(trimmed)
                val dropId = info.dropId ?: ""
                ContactCard(alias = info.alias ?: "Peer", dropId = dropId, fingerprint = info.fingerprint, publicKeyArmored = trimmed)
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    /**
     * Computes the unified 30-digit Safety Code (SAS)
     * derived from the lexicographically sorted fingerprints of both peers.
     *
     * Produces identical code on both devices to verify in-person pairing
     * and prevent MITM attacks (Briar / Bramble protocol style).
     */
    fun calculateSas(myFingerprint: String, peerFingerprint: String): String {
        return StandalonePgpEngine.generateSafetyCode(myFingerprint, peerFingerprint)
    }
}
