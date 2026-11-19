package com.ghostkey.app.data

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import com.ghostkey.app.crypto.SignatureStatus

/**
 * High-performance, immutable representation of a chat message in memory.
 *
 * All fields are read-only (val) and the class is annotated with @Immutable to guarantee
 * optimal Jetpack Compose recomposition stability without UI jank.
 *
 * Anti-bloat guarantee: For media messages, large Base64 envelopes are stripped from RAM
 * immediately upon decryption; only the scaled [mediaBitmap] and lightweight metadata are retained.
 */
@Immutable
data class ChatMessage(
    val id: String,
    val isOutgoing: Boolean,
    val plaintext: String? = null,
    val ciphertext: String,
    val timestamp: Long,
    val isDecrypted: Boolean = false,
    val showRawArmor: Boolean = false,
    val signatureStatus: SignatureStatus? = null,
    val signerFingerprint: String? = null,
    val isMedia: Boolean = false,
    val isAudio: Boolean = false,
    val mimeType: String = "",
    val filename: String = "",
    val mediaBitmap: ImageBitmap? = null,
    val audioBytes: ByteArray? = null,
    val audioDurationMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChatMessage

        if (id != other.id) return false
        if (isOutgoing != other.isOutgoing) return false
        if (plaintext != other.plaintext) return false
        if (ciphertext != other.ciphertext) return false
        if (timestamp != other.timestamp) return false
        if (isDecrypted != other.isDecrypted) return false
        if (showRawArmor != other.showRawArmor) return false
        if (signatureStatus != other.signatureStatus) return false
        if (signerFingerprint != other.signerFingerprint) return false
        if (isMedia != other.isMedia) return false
        if (isAudio != other.isAudio) return false
        if (mimeType != other.mimeType) return false
        if (filename != other.filename) return false
        if (audioDurationMs != other.audioDurationMs) return false
        if (mediaBitmap != other.mediaBitmap) return false
        if (audioBytes != null) {
            if (other.audioBytes == null) return false
            if (!audioBytes.contentEquals(other.audioBytes)) return false
        } else if (other.audioBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + isOutgoing.hashCode()
        result = 31 * result + (plaintext?.hashCode() ?: 0)
        result = 31 * result + ciphertext.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isDecrypted.hashCode()
        result = 31 * result + showRawArmor.hashCode()
        result = 31 * result + (signatureStatus?.hashCode() ?: 0)
        result = 31 * result + (signerFingerprint?.hashCode() ?: 0)
        result = 31 * result + isMedia.hashCode()
        result = 31 * result + isAudio.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + (mediaBitmap?.hashCode() ?: 0)
        result = 31 * result + (audioBytes?.contentHashCode() ?: 0)
        result = 31 * result + audioDurationMs.hashCode()
        return result
    }
}
