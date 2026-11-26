package com.ghostkey.app.data

import androidx.compose.runtime.Immutable

/**
 * Delivery lifecycle of an outgoing message across Tor Relay, Wi-Fi LAN/Direct, and BLE Mesh.
 */
enum class DeliveryState {
    NONE,
    QUEUED,
    SENT_RELAY,
    DELIVERED_LAN,
    RELAYED_BLE_MESH,
    FAILED
}

/**
 * Typed message content inside [ChatMessage], decoupled from UI framework graphics types.
 */
@Immutable
sealed interface MessageContent {
    data object Encrypted : MessageContent

    data class Text(val text: String) : MessageContent

    @Immutable
    data class ImageMedia(
        val mimeType: String,
        val filename: String,
        val imageBytes: ByteArray,
        val width: Int = 0,
        val height: Int = 0
    ) : MessageContent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageMedia) return false
            return mimeType == other.mimeType &&
                filename == other.filename &&
                width == other.width &&
                height == other.height &&
                imageBytes.contentEquals(other.imageBytes)
        }

        override fun hashCode(): Int {
            var r = mimeType.hashCode()
            r = 31 * r + filename.hashCode()
            r = 31 * r + imageBytes.contentHashCode()
            r = 31 * r + width
            r = 31 * r + height
            return r
        }
    }

    @Immutable
    data class FileMedia(
        val mimeType: String,
        val filename: String,
        val sizeBytes: Int = 0,
        val dataBytes: ByteArray? = null
    ) : MessageContent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FileMedia) return false
            return mimeType == other.mimeType &&
                filename == other.filename &&
                sizeBytes == other.sizeBytes &&
                ((dataBytes == null && other.dataBytes == null) ||
                    (dataBytes != null && other.dataBytes != null && dataBytes.contentEquals(other.dataBytes)))
        }

        override fun hashCode(): Int {
            var r = mimeType.hashCode()
            r = 31 * r + filename.hashCode()
            r = 31 * r + sizeBytes
            r = 31 * r + (dataBytes?.contentHashCode() ?: 0)
            return r
        }
    }

    @Immutable
    data class AudioNote(
        val mimeType: String,
        val filename: String,
        val audioBytes: ByteArray,
        val durationMs: Long
    ) : MessageContent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioNote) return false
            return mimeType == other.mimeType &&
                filename == other.filename &&
                durationMs == other.durationMs &&
                audioBytes.contentEquals(other.audioBytes)
        }

        override fun hashCode(): Int {
            var r = mimeType.hashCode()
            r = 31 * r + filename.hashCode()
            r = 31 * r + audioBytes.contentHashCode()
            r = 31 * r + durationMs.hashCode()
            return r
        }
    }

    data class ContactIntro(
        val alias: String,
        val dropId: String,
        val armoredKey: String,
        val safetyCode: String? = null
    ) : MessageContent

    data class GroupInvite(
        val groupId: String,
        val groupName: String,
        val groupDropId: String,
        val groupReadToken: String,
        val sharedKey: String
    ) : MessageContent

    data class GroupControl(
        val groupId: String,
        val action: String,
        val targetDropId: String? = null,
        val newSharedKey: String? = null
    ) : MessageContent

    data object ClearChat : MessageContent

    @Immutable
    data class ProfileUpdate(
        val alias: String? = null,
        val avatarHash: String? = null,
        val avatarBytes: ByteArray? = null
    ) : MessageContent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProfileUpdate) return false
            return alias == other.alias &&
                avatarHash == other.avatarHash &&
                ((avatarBytes == null && other.avatarBytes == null) ||
                    (avatarBytes != null && other.avatarBytes != null && avatarBytes.contentEquals(other.avatarBytes)))
        }

        override fun hashCode(): Int {
            var r = alias?.hashCode() ?: 0
            r = 31 * r + (avatarHash?.hashCode() ?: 0)
            r = 31 * r + (avatarBytes?.contentHashCode() ?: 0)
            return r
        }
    }
}
