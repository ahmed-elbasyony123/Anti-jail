package com.ghostkey.app.data

import androidx.compose.runtime.Immutable
import com.ghostkey.app.crypto.SignatureStatus

/**
 * Immutable representation of a chat message in memory, stripped of raw PGP armor once decrypted.
 */
@Immutable
data class ChatMessage(
    val id: String,
    val isOutgoing: Boolean,
    val content: MessageContent = MessageContent.Encrypted,
    val ciphertext: String = "",
    val timestamp: Long,
    val deliveryState: DeliveryState = DeliveryState.NONE,
    val showRawArmor: Boolean = false,
    val signatureStatus: SignatureStatus? = null,
    val signerFingerprint: String? = null,
    val senderAlias: String? = null,
    val chatId: String = "",
    val groupId: String? = null,
    val isGroup: Boolean = false,
    val replyToId: String? = null,
    val expiresAt: Long? = null,
    val ttlSeconds: Long? = null
) {
    val isDecrypted: Boolean get() = content !== MessageContent.Encrypted
    val isAudio: Boolean get() = content is MessageContent.AudioNote
    val isMedia: Boolean get() = content is MessageContent.ImageMedia ||
        content is MessageContent.FileMedia ||
        content is MessageContent.AudioNote

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != null && now >= expiresAt

    fun remainingSeconds(now: Long = System.currentTimeMillis()): Long? =
        expiresAt?.let { maxOf(0L, (it - now) / 1000L) }

    fun previewText(fallback: String = ""): String = when (content) {
        is MessageContent.Text -> content.text
        is MessageContent.AudioNote -> "🎙️ Voice note"
        is MessageContent.ImageMedia -> "🖼️ Photo"
        is MessageContent.FileMedia -> "📎 ${content.filename.ifBlank { "Attachment" }}"
        is MessageContent.ContactIntro -> "🤝 Introduced contact: ${content.alias}"
        is MessageContent.GroupInvite -> "👥 Group invitation: ${content.groupName}"
        is MessageContent.GroupControl -> "👥 Group update (${content.action})"
        is MessageContent.ProfileUpdate -> "🪪 Profile updated"
        MessageContent.Encrypted -> fallback
    }

    fun zeroOut() {
        when (content) {
            is MessageContent.AudioNote -> content.audioBytes.fill(0)
            is MessageContent.ImageMedia -> content.imageBytes.fill(0)
            is MessageContent.FileMedia -> content.dataBytes?.fill(0)
            is MessageContent.ProfileUpdate -> content.avatarBytes?.fill(0)
            else -> Unit
        }
    }
}
