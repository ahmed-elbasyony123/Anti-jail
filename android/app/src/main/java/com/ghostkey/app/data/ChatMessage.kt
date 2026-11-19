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
    val mimeType: String = "",
    val filename: String = "",
    val mediaBitmap: ImageBitmap? = null
)
