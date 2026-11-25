package com.ghostkey.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.ghostkey.app.audio.AudioRecordResult
import com.ghostkey.app.crypto.GroupCrypto
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.crypto.SignatureStatus
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.crypto.WireFormat
import com.ghostkey.app.crypto.WireMessage
import com.ghostkey.app.media.MediaSanitizer
import com.ghostkey.app.net.EncryptedMessageRecord
import com.ghostkey.app.net.GhostTransport
import com.ghostkey.app.net.RelayHttpException
import com.ghostkey.app.util.GhostKeyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.Collections
import java.util.UUID

internal sealed class ChatTarget {
    data class Direct(val contact: Contact) : ChatTarget()
    data class Group(val group: GroupChat) : ChatTarget()
}

internal data class OutboxItem(
    val msgId: String,
    val isGroup: Boolean,
    val recipientDropId: String,
    val ciphertext: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Unified messaging pipeline coordinating:
 * - Outbox queuing & retry across Tor, Wi-Fi Direct/LAN, and Bitchat BLE Multi-Hop Mesh
 * - Anti-replay nonce & msgId deduplication
 * - EXIF/Metadata sanitization via [MediaSanitizer]
 * - Disappearing message expiry
 * - Encrypted profile picture updates & group control events
 */
internal class MessagingService(
    private val contentResolver: ContentResolver,
    private val transport: GhostTransport
) {
    companion object {
        private const val MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024
        private const val MAX_SEEN_IDS = 2000
        private const val MAX_SENT_CIPHERTEXT_HASHES = 1000
        private const val MAX_OUTBOX_SIZE = 128
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val outbox = Collections.synchronizedList(mutableListOf<OutboxItem>())
    private val seenNonces = Collections.synchronizedSet(LinkedHashSet<String>())
    private val seenMsgIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val sentCiphertextHashes = Collections.synchronizedSet(LinkedHashSet<String>())

    fun clearAllMessages() {
        val current = _messages.value
        for (m in current) m.zeroOut()
        _messages.value = emptyList()
        synchronized(outbox) { outbox.clear() }
        synchronized(seenNonces) { seenNonces.clear() }
        synchronized(seenMsgIds) { seenMsgIds.clear() }
        synchronized(sentCiphertextHashes) { sentCiphertextHashes.clear() }
    }

    fun clearChatMessages(chatId: String) {
        val removed = _messages.value.filter { it.chatId == chatId }
        for (m in removed) m.zeroOut()
        _messages.update { list -> list.filterNot { it.chatId == chatId } }
    }

    fun reapExpiredMessages(now: Long = System.currentTimeMillis()): Boolean {
        val current = _messages.value
        val expired = current.filter { it.isExpired(now) }
        if (expired.isEmpty()) return false
        for (m in expired) m.zeroOut()
        val expiredIds = expired.mapTo(HashSet(expired.size)) { it.id }
        _messages.update { list -> list.filterNot { it.id in expiredIds } }
        return true
    }

    private fun recordUniqueId(set: MutableSet<String>, id: String?): Boolean {
        if (id.isNullOrBlank()) return true
        synchronized(set) {
            if (set.contains(id)) return false
            set.add(id)
            if (set.size > MAX_SEEN_IDS) {
                val oldest = set.iterator().next()
                set.remove(oldest)
            }
            return true
        }
    }

    private fun rememberSentCiphertext(ciphertext: String) {
        val digest = GhostKeyUtils.sha256Hex(ciphertext)
        synchronized(sentCiphertextHashes) {
            sentCiphertextHashes.add(digest)
            if (sentCiphertextHashes.size > MAX_SENT_CIPHERTEXT_HASHES) {
                val oldest = sentCiphertextHashes.iterator().next()
                sentCiphertextHashes.remove(oldest)
            }
        }
    }

    fun isOwnEcho(ciphertext: String): Boolean {
        val digest = GhostKeyUtils.sha256Hex(ciphertext)
        synchronized(sentCiphertextHashes) {
            return sentCiphertextHashes.contains(digest)
        }
    }

    fun insertPendingIncoming(record: EncryptedMessageRecord): ChatMessage? {
        if (isOwnEcho(record.ciphertext)) {
            transport.torClient.ack(listOf(record.id), dropId = record.dropId)
            return null
        }
        val existing = _messages.value.firstOrNull { it.id == record.id }
        if (existing != null) {
            if (existing.isDecrypted) transport.torClient.ack(listOf(existing.id), dropId = record.dropId)
            return null
        }
        val msg = ChatMessage(
            id = record.id,
            isOutgoing = false,
            content = MessageContent.Encrypted,
            ciphertext = record.ciphertext,
            timestamp = record.createdAt,
            chatId = record.dropId
        )
        _messages.update { it + msg }
        return msg
    }

    fun insertMeshIncoming(senderDropId: String, ciphertext: String): ChatMessage? {
        if (isOwnEcho(ciphertext)) return null
        val incoming = ChatMessage(
            id = UUID.randomUUID().toString(),
            isOutgoing = false,
            content = MessageContent.Encrypted,
            ciphertext = ciphertext,
            timestamp = System.currentTimeMillis(),
            chatId = senderDropId
        )
        _messages.update { it + incoming }
        return incoming
    }

    suspend fun decryptAndProcess(
        msg: ChatMessage,
        dropId: String,
        myKeys: PgpKeyPairArmored,
        contacts: List<Contact>,
        groups: List<GroupChat>,
        fromRelay: Boolean,
        onGroupInviteReceived: (GroupChat) -> Unit = {},
        onProfileUpdated: (senderDropId: String, alias: String?, avatarBase64: String?, avatarHash: String?) -> Unit = { _, _, _, _ -> },
        onGroupControlReceived: (MessageContent.GroupControl, senderDropId: String?) -> Unit = { _, _ -> }
    ): ChatMessage? = withContext(Dispatchers.Default) {
        val group = groups.find { it.groupDropId == dropId || it.id == dropId }
        val plainBytes: ByteArray
        var sigStatus: SignatureStatus? = null
        var signerFp: String? = null

        if (group != null) {
            plainBytes = GroupCrypto.decrypt(msg.ciphertext, group.sharedKeyBase64)
        } else {
            val candidateKeys = contacts.mapNotNull { it.publicKeyArmored.ifBlank { null } }.distinct()
            val result = StandalonePgpEngine.decryptMulti(msg.ciphertext, myKeys.privateKeyArmored, candidateKeys)
            plainBytes = result.bytes
            sigStatus = result.signatureStatus
            signerFp = result.signerFingerprint
        }

        val wire = WireFormat.parse(plainBytes)
        val isNewNonce = recordUniqueId(seenNonces, wire?.nonce)
        val isNewMsgId = recordUniqueId(seenMsgIds, wire?.msgId)
        if (!isNewNonce || !isNewMsgId) {
            if (fromRelay) transport.torClient.ack(listOf(msg.id), dropId = group?.groupDropId ?: dropId)
            _messages.update { list -> list.filterNot { it.id == msg.id } }
            return@withContext null
        }

        val matchedContact = signerFp?.let { fp -> contacts.find { it.fingerprint == fp } }
        val senderDrop = matchedContact?.dropId ?: dropId
        val targetChatId = group?.id ?: senderDrop
        val content = decodeWireContent(wire, plainBytes)

        when (content) {
            is MessageContent.GroupInvite -> {
                onGroupInviteReceived(
                    GroupChat(
                        id = content.groupId,
                        name = content.groupName,
                        groupDropId = content.groupDropId,
                        groupReadToken = content.groupReadToken,
                        sharedKeyBase64 = content.sharedKey,
                        members = emptyList(),
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            is MessageContent.ProfileUpdate -> {
                onProfileUpdated(
                    senderDrop,
                    content.alias,
                    wire?.avatarBase64,
                    content.avatarHash
                )
            }
            is MessageContent.GroupControl -> {
                onGroupControlReceived(content, matchedContact?.dropId)
            }
            else -> Unit
        }

        val wireTtl = wire?.ttlSeconds
        val expiresAt = if (wireTtl != null && wireTtl > 0) System.currentTimeMillis() + (wireTtl * 1000L) else null

        val decrypted = msg.copy(
            content = content,
            ciphertext = "",
            signatureStatus = sigStatus,
            signerFingerprint = signerFp,
            chatId = targetChatId,
            groupId = group?.id,
            isGroup = group != null,
            senderAlias = wire?.senderAlias ?: matchedContact?.alias,
            replyToId = wire?.replyTo,
            expiresAt = expiresAt,
            ttlSeconds = wireTtl
        )

        var updated = false
        _messages.update { list ->
            val idx = list.indexOfFirst { it.id == msg.id }
            if (idx == -1) list else {
                updated = true
                list.toMutableList().apply { set(idx, decrypted) }
            }
        }
        if (updated && fromRelay) {
            transport.torClient.ack(listOf(msg.id), dropId = group?.groupDropId ?: dropId)
        }
        if (updated) decrypted else null
    }

    private fun decodeWireContent(wire: WireMessage?, rawPlainBytes: ByteArray): MessageContent {
        if (wire == null) {
            return MessageContent.Text(String(rawPlainBytes, Charsets.UTF_8))
        }
        return when {
            wire.isGroupInvite -> MessageContent.GroupInvite(
                groupId = wire.groupId ?: "",
                groupName = wire.groupName ?: "Encrypted Group",
                groupDropId = wire.groupDropId ?: "",
                groupReadToken = wire.groupReadToken ?: "",
                sharedKey = wire.sharedKey ?: ""
            )
            wire.isIntroduction -> MessageContent.ContactIntro(
                alias = wire.introAlias ?: "Peer",
                dropId = wire.introDropId ?: "",
                armoredKey = wire.introArmoredKey ?: "",
                safetyCode = wire.introSafetyCode
            )
            wire.isProfileUpdate -> MessageContent.ProfileUpdate(
                alias = wire.profileAlias,
                avatarHash = wire.avatarHash,
                avatarBytes = wire.avatarBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            )
            wire.isGroupControl -> MessageContent.GroupControl(
                groupId = wire.groupId ?: "",
                action = wire.groupControlAction ?: "",
                targetDropId = wire.groupControlTargetDropId,
                newSharedKey = wire.groupControlNewKey
            )
            wire.isMedia -> {
                val mimeType = wire.mimeType ?: "application/octet-stream"
                val filename = wire.filename ?: "attachment"
                val raw = runCatching { WireFormat.decodeMediaData(wire) }.getOrDefault(ByteArray(0))
                when {
                    mimeType.startsWith("image/") -> MessageContent.ImageMedia(mimeType, filename, raw)
                    mimeType.startsWith("audio/") -> MessageContent.AudioNote(
                        mimeType,
                        filename,
                        raw,
                        MediaSanitizer.extractAudioDurationMs(raw)
                    )
                    else -> MessageContent.FileMedia(mimeType, filename, raw.size, raw)
                }
            }
            else -> MessageContent.Text(wire.text ?: String(rawPlainBytes, Charsets.UTF_8))
        }
    }

    suspend fun sendText(
        target: ChatTarget,
        text: String,
        myKeys: PgpKeyPairArmored,
        userAlias: String,
        replyToId: String? = null
    ): Result<DeliveryState> = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext Result.success(DeliveryState.NONE)
        val ttl = (target as? ChatTarget.Direct)?.contact?.disappearingTtlSeconds
        val groupId = (target as? ChatTarget.Group)?.group?.id
        val msgId = WireFormat.randomHex16()
        val payload = WireFormat.buildText(text, userAlias, groupId, ttl, replyToId, msgId)
        sendBuiltPayload(target, payload, MessageContent.Text(text), myKeys, userAlias, msgId, replyToId, ttl)
    }

    suspend fun sendMediaUri(
        target: ChatTarget,
        uri: Uri,
        myKeys: PgpKeyPairArmored,
        userAlias: String
    ): Result<DeliveryState> {
        val loaded = withContext(Dispatchers.IO) {
            val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_ATTACHMENT_BYTES) }
                ?: return@withContext Result.failure<Triple<String, String, ByteArray>>(
                    IllegalArgumentException("File exceeds 15 MB limit")
                )
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val name = queryFilename(uri) ?: "attachment"
            Result.success(Triple(mime, name, bytes))
        }
        val (origMime, origName, origBytes) = loaded.getOrElse { return Result.failure(it) }

        return withContext(Dispatchers.Default) {
            val ttl = (target as? ChatTarget.Direct)?.contact?.disappearingTtlSeconds
            val groupId = (target as? ChatTarget.Group)?.group?.id
            val msgId = WireFormat.randomHex16()

            val (finalMime, finalName, finalBytes, content) = if (origMime.startsWith("image/")) {
                val sanitized = MediaSanitizer.sanitizeImage(origBytes)
                if (sanitized != null) {
                    val safeName = origName.substringBeforeLast('.') + ".webp"
                    Tuple4(
                        sanitized.mimeType,
                        safeName,
                        sanitized.bytes,
                        MessageContent.ImageMedia(sanitized.mimeType, safeName, sanitized.bytes, sanitized.width, sanitized.height)
                    )
                } else {
                    Tuple4(origMime, origName, origBytes, MessageContent.ImageMedia(origMime, origName, origBytes))
                }
            } else if (origMime.startsWith("audio/")) {
                val duration = MediaSanitizer.extractAudioDurationMs(origBytes)
                Tuple4(origMime, origName, origBytes, MessageContent.AudioNote(origMime, origName, origBytes, duration))
            } else {
                Tuple4(origMime, origName, origBytes, MessageContent.FileMedia(origMime, origName, origBytes.size, origBytes))
            }

            val payload = WireFormat.buildMedia(finalMime, finalName, finalBytes, userAlias, groupId, ttl, msgId = msgId)
            sendBuiltPayload(target, payload, content, myKeys, userAlias, msgId, ttlSeconds = ttl)
        }
    }

    suspend fun sendAudioNote(
        target: ChatTarget,
        result: AudioRecordResult,
        myKeys: PgpKeyPairArmored,
        userAlias: String
    ): Result<DeliveryState> = withContext(Dispatchers.Default) {
        val ttl = (target as? ChatTarget.Direct)?.contact?.disappearingTtlSeconds
        val groupId = (target as? ChatTarget.Group)?.group?.id
        val msgId = WireFormat.randomHex16()
        val payload = WireFormat.buildMedia(result.mimeType, result.filename, result.bytes, userAlias, groupId, ttl, msgId = msgId)
        sendBuiltPayload(
            target,
            payload,
            MessageContent.AudioNote(result.mimeType, result.filename, result.bytes, result.durationMs),
            myKeys,
            userAlias,
            msgId,
            ttlSeconds = ttl
        )
    }

    suspend fun sendContactIntroduction(
        targetContact: Contact,
        contactToIntroduce: Contact,
        myKeys: PgpKeyPairArmored,
        userAlias: String
    ): Result<DeliveryState> = withContext(Dispatchers.Default) {
        val msgId = WireFormat.randomHex16()
        val wireBytes = WireFormat.buildIntroduction(
            introAlias = contactToIntroduce.alias,
            introDropId = contactToIntroduce.dropId,
            introArmoredKey = contactToIntroduce.publicKeyArmored,
            introSafetyCode = contactToIntroduce.safetyCode,
            senderAlias = userAlias,
            msgId = msgId
        )
        sendBuiltPayload(
            target = ChatTarget.Direct(targetContact),
            payload = wireBytes,
            content = MessageContent.ContactIntro(
                contactToIntroduce.alias,
                contactToIntroduce.dropId,
                contactToIntroduce.publicKeyArmored,
                contactToIntroduce.safetyCode
            ),
            myKeys = myKeys,
            userAlias = userAlias,
            msgId = msgId,
            ttlSeconds = targetContact.disappearingTtlSeconds
        )
    }

    suspend fun broadcastProfileUpdate(
        contacts: List<Contact>,
        myKeys: PgpKeyPairArmored,
        userAlias: String,
        avatarBase64: String?,
        avatarHash: String?
    ) = withContext(Dispatchers.Default) {
        for (contact in contacts) {
            runCatching {
                val payload = WireFormat.buildProfileUpdate(
                    profileAlias = userAlias,
                    avatarBase64 = avatarBase64,
                    avatarHash = avatarHash,
                    senderAlias = userAlias
                )
                val ciphertext = StandalonePgpEngine.encrypt(payload, contact.publicKeyArmored, myKeys.privateKeyArmored)
                rememberSentCiphertext(ciphertext)
                transport.sendEncryptedDirect(contact.dropId, ciphertext)
            }
        }
    }

    suspend fun sendGroupControl(
        group: GroupChat,
        action: String,
        targetDropId: String? = null,
        newSharedKey: String? = null,
        myKeys: PgpKeyPairArmored? = null,
        userAlias: String
    ) = withContext(Dispatchers.Default) {
        val payload = WireFormat.buildGroupControl(
            groupId = group.id,
            action = action,
            targetDropId = targetDropId,
            newSharedKey = newSharedKey,
            senderAlias = userAlias
        )
        if (action == WireMessage.GROUP_ACTION_KEY_ROTATE && newSharedKey != null && myKeys != null) {
            for (member in group.members) {
                runCatching {
                    val ct = StandalonePgpEngine.encrypt(payload, member.publicKeyArmored, myKeys.privateKeyArmored)
                    rememberSentCiphertext(ct)
                    transport.sendEncryptedDirect(member.dropId, ct)
                }
            }
        } else {
            runCatching {
                val ct = GroupCrypto.encrypt(payload, group.sharedKeyBase64)
                rememberSentCiphertext(ct)
                transport.sendEncryptedGroup(group.groupDropId, ct)
            }
        }
    }

    suspend fun inviteMembersToGroup(group: GroupChat, myKeys: PgpKeyPairArmored, userAlias: String) =
        withContext(Dispatchers.Default) {
            for (member in group.members) {
                runCatching {
                    val invitePayload = WireFormat.buildGroupInvite(
                        groupId = group.id,
                        groupName = group.name,
                        groupDropId = group.groupDropId,
                        groupReadToken = group.groupReadToken,
                        sharedKey = group.sharedKeyBase64,
                        senderAlias = userAlias
                    )
                    val ciphertext = StandalonePgpEngine.encrypt(invitePayload, member.publicKeyArmored, myKeys.privateKeyArmored)
                    rememberSentCiphertext(ciphertext)
                    transport.sendEncryptedDirect(member.dropId, ciphertext)
                }
            }
        }

    suspend fun flushOutbox() = withContext(Dispatchers.IO) {
        val snapshot = synchronized(outbox) { outbox.toList() }
        for (item in snapshot) {
            val res = if (item.isGroup) {
                transport.sendEncryptedGroup(item.recipientDropId, item.ciphertext)
            } else {
                transport.sendEncryptedDirect(item.recipientDropId, item.ciphertext)
            }
            val newState = res.getOrNull() ?: continue
            synchronized(outbox) { outbox.removeAll { it.msgId == item.msgId } }
            _messages.update { list ->
                list.map { if (it.id == item.msgId) it.copy(deliveryState = newState) else it }
            }
        }
    }

    private suspend fun sendBuiltPayload(
        target: ChatTarget,
        payload: ByteArray,
        content: MessageContent,
        myKeys: PgpKeyPairArmored,
        userAlias: String,
        msgId: String,
        replyToId: String? = null,
        ttlSeconds: Long? = null
    ): Result<DeliveryState> {
        val (recipientDrop, isGroup, ciphertext) = when (target) {
            is ChatTarget.Group -> Triple(
                target.group.groupDropId,
                true,
                GroupCrypto.encrypt(payload, target.group.sharedKeyBase64)
            )
            is ChatTarget.Direct -> Triple(
                target.contact.dropId,
                false,
                StandalonePgpEngine.encrypt(payload, target.contact.publicKeyArmored, myKeys.privateKeyArmored)
            )
        }
        rememberSentCiphertext(ciphertext)

        val deliveryResult = if (isGroup) {
            transport.sendEncryptedGroup(recipientDrop, ciphertext)
        } else {
            transport.sendEncryptedDirect(recipientDrop, ciphertext)
        }

        val state = deliveryResult.getOrElse {
            enqueueOutbox(OutboxItem(msgId, isGroup, recipientDrop, ciphertext))
            DeliveryState.QUEUED
        }

        val expires = if (ttlSeconds != null && ttlSeconds > 0) System.currentTimeMillis() + (ttlSeconds * 1000L) else null
        val chatId = when (target) {
            is ChatTarget.Direct -> target.contact.dropId
            is ChatTarget.Group -> target.group.id
        }
        val msg = ChatMessage(
            id = msgId,
            isOutgoing = true,
            content = content,
            ciphertext = "",
            timestamp = System.currentTimeMillis(),
            deliveryState = state,
            chatId = chatId,
            groupId = (target as? ChatTarget.Group)?.group?.id,
            isGroup = isGroup,
            senderAlias = userAlias,
            replyToId = replyToId,
            expiresAt = expires,
            ttlSeconds = ttlSeconds
        )
        _messages.update { it + msg }
        return if (deliveryResult.isSuccess) Result.success(state) else deliveryResult
    }

    private fun enqueueOutbox(item: OutboxItem) {
        synchronized(outbox) {
            if (outbox.size >= MAX_OUTBOX_SIZE) outbox.removeAt(0)
            outbox.add(item)
        }
    }

    fun formatFriendlySendError(err: Throwable): String {
        val reason = when ((err as? RelayHttpException)?.code) {
            429 -> "rate limit reached, try again shortly"
            503 -> "the relay is at capacity"
            413 -> "message is too large for the relay"
            else -> err.message
        }
        return "Queued/Send note: $reason"
    }

    fun buildChatSummaries(
        contacts: List<Contact>,
        groups: List<GroupChat>,
        allMessages: List<ChatMessage>
    ): List<ChatSummary> {
        val list = ArrayList<ChatSummary>(groups.size + contacts.size)
        val knownChatIds = HashSet<String>(groups.size * 2 + contacts.size)

        for (g in groups) {
            knownChatIds.add(g.id)
            knownChatIds.add(g.groupDropId)
            val lastMsg = allMessages.lastOrNull { it.chatId == g.id }
            list.add(
                ChatSummary(
                    chatId = g.id,
                    title = g.name,
                    subtitle = "${g.members.size} members",
                    isGroup = true,
                    lastMessageText = lastMsg?.previewText("Group session ready") ?: "Group session ready",
                    lastMessageTime = lastMsg?.timestamp ?: g.createdAt,
                    avatarLetter = g.name.take(1)
                )
            )
        }

        for (c in contacts) {
            knownChatIds.add(c.dropId)
            val lastMsg = allMessages.lastOrNull { it.chatId == c.dropId }
            list.add(
                ChatSummary(
                    chatId = c.dropId,
                    title = c.alias,
                    subtitle = "Peer ${c.fingerprint.take(8)}",
                    isGroup = false,
                    lastMessageText = lastMsg?.previewText("Direct E2EE session") ?: "Direct E2EE session",
                    lastMessageTime = lastMsg?.timestamp ?: c.addedAt,
                    avatarLetter = c.alias.take(1),
                    isVerified = c.isVerified
                )
            )
        }

        val unknownByChat = allMessages
            .filter { it.chatId !in knownChatIds && !it.isOutgoing }
            .groupBy { it.chatId }

        for ((cid, msgs) in unknownByChat) {
            val lastMsg = msgs.lastOrNull() ?: continue
            val sender = lastMsg.senderAlias ?: lastMsg.signerFingerprint?.take(8) ?: "Unknown"
            list.add(
                ChatSummary(
                    chatId = cid,
                    title = "Unknown ($sender)",
                    subtitle = "Unsaved Contact",
                    isGroup = false,
                    lastMessageText = lastMsg.previewText("Incoming message"),
                    lastMessageTime = lastMsg.timestamp,
                    avatarLetter = sender.take(1).uppercase()
                )
            )
        }

        return list.sortedByDescending { it.lastMessageTime }
    }

    private fun queryFilename(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return null
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
