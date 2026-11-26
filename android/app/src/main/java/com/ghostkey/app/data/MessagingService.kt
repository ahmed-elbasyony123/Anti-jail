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
 * - Bounded RAM Outbox queuing & retry across Tor, Wi-Fi Direct/LAN, and BLE Mesh
 * - Anti-replay nonce & msgId deduplication
 * - EXIF/Metadata sanitization via [MediaSanitizer] with 15 MB raw cap (<= 32 MB server limit after PGP armor)
 * - Disappearing message expiry, E2EE profile updates, and group control events
 */
internal class MessagingService(
    private val contentResolver: ContentResolver,
    private val transport: GhostTransport
) {
    companion object {
        // 15 MB raw media expands to ~28.1 MB after Base64 + OpenPGP armor (under 32 MB relay cap)
        const val MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024
        const val MAX_OUTBOX_BYTES = 32 * 1024 * 1024L
        private const val MAX_SEEN_IDS = 2000
        private const val MAX_SENT_CIPHERTEXT_HASHES = 1000
        private const val MAX_OUTBOX_SIZE = 128
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val outbox = Collections.synchronizedList(mutableListOf<OutboxItem>())
    private var outboxTotalBytes = 0L
    private val seenNonces = Collections.synchronizedSet(LinkedHashSet<String>())
    private val seenMsgIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val sentCiphertextHashes = Collections.synchronizedSet(LinkedHashSet<String>())

    fun clearAllMessages() {
        _messages.value.forEach { it.zeroOut() }
        _messages.value = emptyList()
        synchronized(outbox) {
            outbox.clear()
            outboxTotalBytes = 0L
        }
        synchronized(seenNonces) { seenNonces.clear() }
        synchronized(seenMsgIds) { seenMsgIds.clear() }
        synchronized(sentCiphertextHashes) { sentCiphertextHashes.clear() }
    }

    fun clearChatMessages(chatId: String) {
        _messages.value.filter { it.chatId == chatId }.forEach { it.zeroOut() }
        _messages.update { list -> list.filterNot { it.chatId == chatId } }
    }

    fun reapExpiredMessages(now: Long = System.currentTimeMillis()): Boolean {
        val expired = _messages.value.filter { it.isExpired(now) }
        if (expired.isEmpty()) return false
        expired.forEach { it.zeroOut() }
        val expiredIds = expired.mapTo(HashSet(expired.size)) { it.id }
        _messages.update { list -> list.filterNot { it.id in expiredIds } }
        return true
    }

    private fun addBoundedUnique(set: MutableSet<String>, maxEntries: Int, id: String?): Boolean {
        if (id.isNullOrBlank()) return true
        synchronized(set) {
            if (!set.add(id)) return false
            if (set.size > maxEntries) set.remove(set.iterator().next())
            return true
        }
    }

    private fun rememberSentCiphertext(ciphertext: String) {
        addBoundedUnique(sentCiphertextHashes, MAX_SENT_CIPHERTEXT_HASHES, GhostKeyUtils.sha256Hex(ciphertext))
    }

    fun isOwnEcho(ciphertext: String): Boolean {
        val digest = GhostKeyUtils.sha256Hex(ciphertext)
        synchronized(sentCiphertextHashes) { return sentCiphertextHashes.contains(digest) }
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

    private data class DecryptedEnvelope(
        val plainBytes: ByteArray,
        val sigStatus: SignatureStatus?,
        val signerFp: String?,
        val group: GroupChat?
    )

    private fun decryptEnvelope(
        msg: ChatMessage,
        dropId: String,
        myKeys: PgpKeyPairArmored,
        contacts: List<Contact>,
        groups: List<GroupChat>
    ): DecryptedEnvelope {
        val group = groups.find { it.groupDropId == dropId || it.id == dropId }
        if (group != null) {
            return DecryptedEnvelope(GroupCrypto.decrypt(msg.ciphertext, group.sharedKeyBase64), null, null, group)
        }
        val candidateKeys = contacts.mapNotNull { it.publicKeyArmored.ifBlank { null } }.distinct()
        val result = StandalonePgpEngine.decryptMulti(msg.ciphertext, myKeys.privateKeyArmored, candidateKeys)
        return DecryptedEnvelope(result.bytes, result.signatureStatus, result.signerFingerprint, null)
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
        onGroupControlReceived: (MessageContent.GroupControl, senderDropId: String?) -> Unit = { _, _ -> },
        onChatClearedByPeer: (senderDropId: String, alias: String) -> Unit = { _, _ -> },
        onContactRequestReceived: (ContactRequest) -> Unit = {}
    ): ChatMessage? = withContext(Dispatchers.Default) {
        val env = decryptEnvelope(msg, dropId, myKeys, contacts, groups)
        val wire = WireFormat.parse(env.plainBytes)
        val isNewNonce = addBoundedUnique(seenNonces, MAX_SEEN_IDS, wire?.nonce)
        val isNewMsgId = addBoundedUnique(seenMsgIds, MAX_SEEN_IDS, wire?.msgId)
        if (!isNewNonce || !isNewMsgId) {
            if (fromRelay) transport.torClient.ack(listOf(msg.id), dropId = env.group?.groupDropId ?: dropId)
            _messages.update { list -> list.filterNot { it.id == msg.id } }
            return@withContext null
        }

        if (wire != null && wire.isContactRequest && env.group == null) {
            val rawKey = wire.requestPublicKeyArmored
            val embedded = rawKey?.let { runCatching { StandalonePgpEngine.parsePeerKey(it) }.getOrNull() }
            val sigVerified = if (embedded != null &&
                embedded.dropId == wire.requestDropId &&
                embedded.fingerprint == wire.requestFingerprint &&
                embedded.dropId != myKeys.dropId
            ) {
                val check = runCatching {
                    StandalonePgpEngine.decryptMulti(
                        msg.ciphertext,
                        myKeys.privateKeyArmored,
                        listOf(embedded.publicKeyArmored)
                    )
                }.getOrNull()
                check?.signatureStatus == SignatureStatus.VERIFIED &&
                    check.signerFingerprint == embedded.fingerprint
            } else {
                false
            }
            if (sigVerified && embedded != null) {
                onContactRequestReceived(
                    ContactRequest(
                        dropId = embedded.dropId,
                        alias = wire.requestAlias ?: "Peer ${embedded.fingerprint.take(6)}",
                        fingerprint = embedded.fingerprint,
                        publicKeyArmored = embedded.publicKeyArmored,
                        avatarBase64 = wire.avatarBase64,
                        avatarHash = wire.avatarHash
                    )
                )
            }
            _messages.update { list -> list.filterNot { it.id == msg.id } }
            if (fromRelay) {
                transport.torClient.ack(listOf(msg.id), dropId = dropId)
            }
            return@withContext null
        }

        val matchedContact = env.signerFp?.let { fp -> contacts.find { it.fingerprint == fp } }
        val senderDrop = matchedContact?.dropId ?: dropId
        val targetChatId = env.group?.id ?: senderDrop
        val content = decodeWireContent(wire, env.plainBytes)

        if (content is MessageContent.ClearChat) {
            if (matchedContact != null && env.sigStatus == SignatureStatus.VERIFIED && env.group == null) {
                clearChatMessages(matchedContact.dropId)
                onChatClearedByPeer(matchedContact.dropId, matchedContact.alias)
            }
            _messages.update { list -> list.filterNot { it.id == msg.id } }
            if (fromRelay) {
                transport.torClient.ack(listOf(msg.id), dropId = env.group?.groupDropId ?: dropId)
            }
            return@withContext null
        }

        dispatchSpecialContent(
            content = content,
            wire = wire,
            senderDrop = senderDrop,
            verifiedSenderDrop = matchedContact?.dropId,
            onGroupInviteReceived = onGroupInviteReceived,
            onProfileUpdated = onProfileUpdated,
            onGroupControlReceived = onGroupControlReceived
        )

        val wireTtl = wire?.ttlSeconds
        val expiresAt = if (wireTtl != null && wireTtl > 0) System.currentTimeMillis() + (wireTtl * 1000L) else null
        val decrypted = msg.copy(
            content = content,
            ciphertext = "",
            signatureStatus = env.sigStatus,
            signerFingerprint = env.signerFp,
            chatId = targetChatId,
            groupId = env.group?.id,
            isGroup = env.group != null,
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
            transport.torClient.ack(listOf(msg.id), dropId = env.group?.groupDropId ?: dropId)
        }
        if (updated) decrypted else null
    }

    private fun dispatchSpecialContent(
        content: MessageContent,
        wire: WireMessage?,
        senderDrop: String,
        verifiedSenderDrop: String?,
        onGroupInviteReceived: (GroupChat) -> Unit,
        onProfileUpdated: (senderDropId: String, alias: String?, avatarBase64: String?, avatarHash: String?) -> Unit,
        onGroupControlReceived: (MessageContent.GroupControl, senderDropId: String?) -> Unit
    ) {
        when (content) {
            is MessageContent.GroupInvite -> onGroupInviteReceived(
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
            is MessageContent.ProfileUpdate -> onProfileUpdated(senderDrop, content.alias, wire?.avatarBase64, content.avatarHash)
            is MessageContent.GroupControl -> onGroupControlReceived(content, verifiedSenderDrop)
            else -> Unit
        }
    }

    private fun decodeWireContent(wire: WireMessage?, rawPlainBytes: ByteArray): MessageContent {
        if (wire == null) {
            if (WireFormat.isStructuredEnvelope(rawPlainBytes)) {
                return MessageContent.Text("⚠️ This message is not supported on your version of GhostKey.")
            }
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
            wire.isClearChat -> MessageContent.ClearChat
            wire.isContactRequest -> MessageContent.ContactRequest(
                alias = wire.requestAlias ?: "Peer",
                dropId = wire.requestDropId ?: "",
                fingerprint = wire.requestFingerprint ?: "",
                publicKeyArmored = wire.requestPublicKeyArmored ?: "",
                avatarHash = wire.avatarHash,
                avatarBase64 = wire.avatarBase64
            )
            wire.isMedia -> {
                val mimeType = wire.mimeType ?: "application/octet-stream"
                val filename = wire.filename ?: "attachment"
                val raw = runCatching { WireFormat.decodeMediaData(wire) }.getOrDefault(ByteArray(0))
                when {
                    mimeType.startsWith("image/") -> MessageContent.ImageMedia(mimeType, filename, raw)
                    mimeType.startsWith("audio/") -> MessageContent.AudioNote(
                        mimeType, filename, raw, MediaSanitizer.extractAudioDurationMs(raw)
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

    private data class PreparedMedia(
        val mimeType: String,
        val filename: String,
        val bytes: ByteArray,
        val content: MessageContent
    )

    suspend fun sendMediaUri(
        target: ChatTarget,
        uri: Uri,
        myKeys: PgpKeyPairArmored,
        userAlias: String
    ): Result<DeliveryState> {
        val loaded = withContext(Dispatchers.IO) {
            val bytes = contentResolver.openInputStream(uri)?.use {
                GhostKeyUtils.readStreamLimited(it, MAX_ATTACHMENT_BYTES)
            } ?: return@withContext Result.failure<Triple<String, String, ByteArray>>(
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
            val prepared = prepareOutgoingMedia(origMime, origName, origBytes)
            val payload = WireFormat.buildMedia(prepared.mimeType, prepared.filename, prepared.bytes, userAlias, groupId, ttl, msgId = msgId)
            sendBuiltPayload(target, payload, prepared.content, myKeys, userAlias, msgId, ttlSeconds = ttl)
        }
    }

    private fun prepareOutgoingMedia(origMime: String, origName: String, origBytes: ByteArray): PreparedMedia = when {
        origMime.startsWith("image/") -> {
            val sanitized = MediaSanitizer.sanitizeImage(origBytes)
            if (sanitized != null) {
                val safeName = origName.substringBeforeLast('.') + ".webp"
                PreparedMedia(
                    sanitized.mimeType,
                    safeName,
                    sanitized.bytes,
                    MessageContent.ImageMedia(sanitized.mimeType, safeName, sanitized.bytes, sanitized.width, sanitized.height)
                )
            } else {
                PreparedMedia(origMime, origName, origBytes, MessageContent.ImageMedia(origMime, origName, origBytes))
            }
        }
        origMime.startsWith("audio/") -> {
            val duration = MediaSanitizer.extractAudioDurationMs(origBytes)
            PreparedMedia(origMime, origName, origBytes, MessageContent.AudioNote(origMime, origName, origBytes, duration))
        }
        else -> PreparedMedia(origMime, origName, origBytes, MessageContent.FileMedia(origMime, origName, origBytes.size, origBytes))
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
                contactToIntroduce.alias, contactToIntroduce.dropId, contactToIntroduce.publicKeyArmored, contactToIntroduce.safetyCode
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
        val payload = WireFormat.buildProfileUpdate(
            profileAlias = userAlias,
            avatarBase64 = avatarBase64,
            avatarHash = avatarHash,
            senderAlias = userAlias
        )
        for (contact in contacts) {
            runCatching { encryptAndSendDirect(contact.dropId, contact.publicKeyArmored, payload, myKeys) }
        }
    }

    suspend fun sendClearChat(
        contact: Contact,
        myKeys: PgpKeyPairArmored,
        userAlias: String
    ): Result<DeliveryState> = withContext(Dispatchers.Default) {
        clearChatMessages(contact.dropId)
        val payload = WireFormat.buildClearChat(senderAlias = userAlias)
        runCatching {
            val (_, sendRes) = encryptAndSendDirect(contact.dropId, contact.publicKeyArmored, payload, myKeys)
            sendRes.getOrThrow()
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
                runCatching { encryptAndSendDirect(member.dropId, member.publicKeyArmored, payload, myKeys) }
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
            val invitePayload = WireFormat.buildGroupInvite(
                groupId = group.id,
                groupName = group.name,
                groupDropId = group.groupDropId,
                groupReadToken = group.groupReadToken,
                sharedKey = group.sharedKeyBase64,
                senderAlias = userAlias
            )
            for (member in group.members) {
                runCatching { encryptAndSendDirect(member.dropId, member.publicKeyArmored, invitePayload, myKeys) }
            }
        }

    suspend fun sendContactRequest(
        peer: Contact,
        myKeys: PgpKeyPairArmored,
        userAlias: String,
        avatarBase64: String?,
        avatarHash: String?
    ): Result<DeliveryState> = withContext(Dispatchers.Default) {
        val payload = WireFormat.buildContactRequest(
            alias = userAlias.ifBlank { "Peer ${myKeys.fingerprint.take(6)}" },
            dropId = myKeys.dropId,
            fingerprint = myKeys.fingerprint,
            publicKeyArmored = myKeys.publicKeyArmored,
            avatarBase64 = avatarBase64,
            avatarHash = avatarHash,
            senderAlias = userAlias.ifBlank { null }
        )
        runCatching {
            val (_, delivery) = encryptAndSendDirect(peer.dropId, peer.publicKeyArmored, payload, myKeys)
            delivery.getOrThrow()
        }
    }

    private suspend fun encryptAndSendDirect(
        recipientDropId: String,
        recipientPubKey: String,
        payload: ByteArray,
        myKeys: PgpKeyPairArmored
    ): Pair<String, Result<DeliveryState>> {
        val ciphertext = StandalonePgpEngine.encrypt(payload, recipientPubKey, myKeys.privateKeyArmored)
        rememberSentCiphertext(ciphertext)
        return ciphertext to transport.sendEncryptedDirect(recipientDropId, ciphertext)
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
            synchronized(outbox) {
                val iterator = outbox.iterator()
                while (iterator.hasNext()) {
                    val cur = iterator.next()
                    if (cur.msgId == item.msgId) {
                        outboxTotalBytes = (outboxTotalBytes - cur.ciphertext.length).coerceAtLeast(0L)
                        iterator.remove()
                    }
                }
            }
            _messages.update { list -> list.map { if (it.id == item.msgId) it.copy(deliveryState = newState) else it } }
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
        val isGroup = target is ChatTarget.Group
        val recipientDrop = when (target) {
            is ChatTarget.Group -> target.group.groupDropId
            is ChatTarget.Direct -> target.contact.dropId
        }
        val groupId = (target as? ChatTarget.Group)?.group?.id
        val chatId = groupId ?: (target as ChatTarget.Direct).contact.dropId
        val expires = if (ttlSeconds != null && ttlSeconds > 0) System.currentTimeMillis() + (ttlSeconds * 1000L) else null

        // Optimistic UI insert: render immediately in chat with QUEUED (⏳) before Tor round-trip
        val pendingMsg = ChatMessage(
            id = msgId,
            isOutgoing = true,
            content = content,
            ciphertext = "",
            timestamp = System.currentTimeMillis(),
            deliveryState = DeliveryState.QUEUED,
            chatId = chatId,
            groupId = groupId,
            isGroup = isGroup,
            senderAlias = userAlias,
            replyToId = replyToId,
            expiresAt = expires,
            ttlSeconds = ttlSeconds
        )
        _messages.update { it + pendingMsg }

        val ciphertext = try {
            when (target) {
                is ChatTarget.Group -> GroupCrypto.encrypt(payload, target.group.sharedKeyBase64)
                is ChatTarget.Direct -> StandalonePgpEngine.encrypt(payload, target.contact.publicKeyArmored, myKeys.privateKeyArmored)
            }.also { rememberSentCiphertext(it) }
        } catch (e: Exception) {
            _messages.update { list -> list.map { if (it.id == msgId) it.copy(deliveryState = DeliveryState.FAILED) else it } }
            return Result.failure(e)
        }

        val deliveryResult = if (isGroup) {
            transport.sendEncryptedGroup(recipientDrop, ciphertext)
        } else {
            transport.sendEncryptedDirect(recipientDrop, ciphertext)
        }

        val finalState = deliveryResult.getOrElse {
            if (enqueueOutbox(OutboxItem(msgId, isGroup, recipientDrop, ciphertext))) DeliveryState.QUEUED else DeliveryState.FAILED
        }
        _messages.update { list -> list.map { if (it.id == msgId) it.copy(deliveryState = finalState) else it } }
        return if (deliveryResult.isSuccess) Result.success(finalState) else deliveryResult
    }

    private fun enqueueOutbox(item: OutboxItem): Boolean {
        val itemBytes = item.ciphertext.length.toLong()
        if (itemBytes > MAX_OUTBOX_BYTES) return false
        synchronized(outbox) {
            while ((outbox.size >= MAX_OUTBOX_SIZE || outboxTotalBytes + itemBytes > MAX_OUTBOX_BYTES) && outbox.isNotEmpty()) {
                val removed = outbox.removeAt(0)
                outboxTotalBytes = (outboxTotalBytes - removed.ciphertext.length).coerceAtLeast(0L)
            }
            outbox.add(item)
            outboxTotalBytes += itemBytes
        }
        return true
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
        allMessages: List<ChatMessage>,
        lastReadByChat: Map<String, Long> = emptyMap()
    ): List<ChatSummary> {
        val list = ArrayList<ChatSummary>(groups.size + contacts.size)
        val knownChatIds = HashSet<String>(groups.size * 2 + contacts.size)

        fun countUnread(chatId: String): Int {
            val lastRead = lastReadByChat[chatId] ?: 0L
            return allMessages.count { !it.isOutgoing && it.chatId == chatId && it.timestamp > lastRead }
        }

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
                    unreadCount = countUnread(g.id),
                    avatarLetter = g.name.take(1),
                    isPinned = g.isPinned,
                    isMuted = g.isMuted,
                    isArchived = g.isArchived
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
                    unreadCount = countUnread(c.dropId),
                    avatarLetter = c.alias.take(1),
                    avatarBase64 = c.avatarBase64,
                    isVerified = c.isVerified,
                    keyChangedWarning = c.keyChangedWarning,
                    isPinned = c.isPinned,
                    isMuted = c.isMuted,
                    isArchived = c.isArchived
                )
            )
        }

        val unknownByChat = allMessages.filter { it.chatId !in knownChatIds && !it.isOutgoing }.groupBy { it.chatId }
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
                    unreadCount = countUnread(cid),
                    avatarLetter = sender.take(1).uppercase()
                )
            )
        }
        return list.sortedWith(
            compareByDescending<ChatSummary> { it.isPinned }.thenByDescending { it.lastMessageTime }
        )
    }

    private fun queryFilename(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return null
    }
}
