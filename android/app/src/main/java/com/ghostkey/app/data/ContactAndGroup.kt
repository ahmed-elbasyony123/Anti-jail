package com.ghostkey.app.data

import androidx.compose.runtime.Immutable

@Immutable
data class Contact(
    val id: String, // dropId or fingerprint
    val alias: String, // display nickname
    val dropId: String,
    val publicKeyArmored: String,
    val fingerprint: String,
    val addedAt: Long = System.currentTimeMillis(),
    val isVerified: Boolean = false,
    val safetyCode: String? = null,
    val disappearingTtlSeconds: Long? = null
)

@Immutable
data class GroupMember(
    val dropId: String,
    val alias: String,
    val publicKeyArmored: String,
    val fingerprint: String
)

@Immutable
data class GroupChat(
    val id: String, // unique UUID or hex
    val name: String,
    val groupDropId: String,
    val groupReadToken: String,
    val sharedKeyBase64: String,
    val members: List<GroupMember>,
    val createdAt: Long = System.currentTimeMillis(),
    val isAdmin: Boolean = false
)

/**
 * Summary representation of a 1-to-1 or group chat for the Telegram-style chat list.
 */
@Immutable
data class ChatSummary(
    val chatId: String,
    val title: String,
    val subtitle: String = "",
    val isGroup: Boolean = false,
    val lastMessageText: String = "",
    val lastMessageTime: Long = 0L,
    val unreadCount: Int = 0,
    val avatarLetter: String = "G",
    val isOnline: Boolean = true,
    val isVerified: Boolean = false
)
