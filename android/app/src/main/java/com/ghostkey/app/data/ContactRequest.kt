package com.ghostkey.app.data

import androidx.compose.runtime.Immutable

/**
 * Cryptographically-verified incoming contact request queued for user confirmation
 * (Accept / Decline banner in ChatsListScreen).
 */
@Immutable
data class ContactRequest(
    val dropId: String,
    val alias: String,
    val fingerprint: String,
    val publicKeyArmored: String,
    val avatarBase64: String? = null,
    val avatarHash: String? = null,
    val receivedAt: Long = System.currentTimeMillis()
)
