package com.ghostkey.app.data

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ghostkey.app.audio.AudioRecordResult
import com.ghostkey.app.audio.MemoryAudioDataSource
import com.ghostkey.app.crypto.GroupCrypto
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.crypto.SignatureStatus
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.crypto.WireFormat
import com.ghostkey.app.crypto.WireMessage
import com.ghostkey.app.net.EncryptedMessageRecord
import com.ghostkey.app.net.LocalLanMesh
import com.ghostkey.app.net.RelayHttpException
import com.ghostkey.app.net.TorRelayClient
import com.ghostkey.app.util.GhostKeyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Collections
import java.util.UUID

internal data class DecodedMediaPreview(
    val isMedia: Boolean = false,
    val isAudio: Boolean = false,
    val mimeType: String = "",
    val filename: String = "",
    val bitmap: ImageBitmap? = null,
    val audioBytes: ByteArray? = null,
    val audioDurationMs: Long = 0L,
    val displayText: String? = null,
    val isIntroduction: Boolean = false,
    val introAlias: String? = null,
    val introDropId: String? = null,
    val introArmoredKey: String? = null,
    val introSafetyCode: String? = null,
    val groupInvite: GroupChat? = null
)

internal sealed class ChatTarget {
    data class Direct(val contact: Contact) : ChatTarget()
    data class Group(val group: GroupChat) : ChatTarget()
}

/**
 * Coordinates message encryption, decryption, anti-replay deduplication, media decoding,
 * disappearing message expiry, and delivery over Local LAN Mesh or Tor Relay.
 */
internal class MessagingRepository(
    private val contentResolver: ContentResolver,
    private val torClient: TorRelayClient
) {
    companion object {
        private const val MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024
        private const val MAX_BUBBLE_IMAGE_EDGE = 1024
        private const val MAX_SEEN_NONCES = 2000
        private const val MAX_SENT_CIPHERTEXT_HASHES = 1000
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val seenNonces = Collections.synchronizedSet(LinkedHashSet<String>())
    private val sentCiphertextHashes = Collections.synchronizedSet(LinkedHashSet<String>())

    fun clearAllMessages() {
        val current = _messages.value
        for (m in current) m.zeroOut()
        _messages.value = emptyList()
        synchronized(seenNonces) { seenNonces.clear() }
        synchronized(sentCiphertextHashes) { sentCiphertextHashes.clear() }
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

    private fun recordNonceIfNew(nonce: String?): Boolean {
        if (nonce == null) return true
        synchronized(seenNonces) {
            if (seenNonces.contains(nonce)) return false
            seenNonces.add(nonce)
            if (seenNonces.size > MAX_SEEN_NONCES) {
                val oldest = seenNonces.iterator().next()
                seenNonces.remove(oldest)
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
            torClient.ack(listOf(record.id), dropId = record.dropId)
            return null
        }
        val existing = _messages.value.firstOrNull { it.id == record.id }
        if (existing != null) {
            if (existing.isDecrypted) torClient.ack(listOf(existing.id), dropId = record.dropId)
            return null
        }
        val msg = ChatMessage(
            id = record.id,
            isOutgoing = false,
            plaintext = null,
            ciphertext = record.ciphertext,
            timestamp = record.createdAt,
            isDecrypted = false,
            chatId = record.dropId
        )
        _messages.update { it + msg }
        return msg
    }

    fun insertLanIncoming(senderDropId: String, ciphertext: String): ChatMessage? {
        val incoming = ChatMessage(
            id = UUID.randomUUID().toString(),
            isOutgoing = false,
            ciphertext = ciphertext,
            timestamp = System.currentTimeMillis(),
            isDecrypted = false,
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
        onGroupInviteReceived: (GroupChat) -> Unit
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
        if (!recordNonceIfNew(wire?.nonce)) {
            if (fromRelay) torClient.ack(listOf(msg.id), dropId = group?.groupDropId ?: dropId)
            _messages.update { list -> list.filterNot { it.id == msg.id } }
            return@withContext null
        }

        val decoded = decodeWirePayload(wire, plainBytes)
        decoded.groupInvite?.let(onGroupInviteReceived)

        val matchedContact = signerFp?.let { fp -> contacts.find { it.fingerprint == fp } }
        val targetChatId = group?.id ?: matchedContact?.dropId ?: dropId
        val wireTtl = wire?.ttlSeconds
        val expiresAt = if (wireTtl != null && wireTtl > 0) System.currentTimeMillis() + (wireTtl * 1000L) else null

        val decrypted = msg.copy(
            plaintext = decoded.displayText,
            ciphertext = "", // Strip large armored envelope from RAM once decrypted
            isDecrypted = true,
            isMedia = decoded.isMedia,
            isAudio = decoded.isAudio,
            mimeType = decoded.mimeType,
            filename = decoded.filename,
            mediaBitmap = decoded.bitmap,
            audioBytes = decoded.audioBytes,
            audioDurationMs = decoded.audioDurationMs,
            signatureStatus = sigStatus,
            signerFingerprint = signerFp,
            chatId = targetChatId,
            groupId = group?.id,
            isGroup = group != null,
            senderAlias = wire?.senderAlias ?: matchedContact?.alias,
            expiresAt = expiresAt,
            ttlSeconds = wireTtl,
            isIntroduction = decoded.isIntroduction,
            introAlias = decoded.introAlias,
            introDropId = decoded.introDropId,
            introArmoredKey = decoded.introArmoredKey,
            introSafetyCode = decoded.introSafetyCode
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
            torClient.ack(listOf(msg.id), dropId = group?.groupDropId ?: dropId)
        }
        if (updated) decrypted else null
    }

    private fun decodeWirePayload(wire: WireMessage?, rawPlainBytes: ByteArray): DecodedMediaPreview {
        if (wire != null && wire.isGroupInvite) {
            val groupName = wire.groupName ?: "Encrypted Group"
            val invite = if (!wire.groupId.isNullOrBlank() && !wire.sharedKey.isNullOrBlank() &&
                !wire.groupDropId.isNullOrBlank() && !wire.groupReadToken.isNullOrBlank()
            ) {
                GroupChat(
                    id = wire.groupId,
                    name = groupName,
                    groupDropId = wire.groupDropId,
                    groupReadToken = wire.groupReadToken,
                    sharedKeyBase64 = wire.sharedKey,
                    members = emptyList(),
                    createdAt = System.currentTimeMillis()
                )
            } else null
            return DecodedMediaPreview(
                displayText = "Group invitation: $groupName",
                groupInvite = invite
            )
        }

        if (wire != null && wire.isIntroduction) {
            return DecodedMediaPreview(
                displayText = "🤝 Introduced contact: ${wire.introAlias}",
                isIntroduction = true,
                introAlias = wire.introAlias,
                introDropId = wire.introDropId,
                introArmoredKey = wire.introArmoredKey,
                introSafetyCode = wire.introSafetyCode
            )
        }

        if (wire != null && wire.isMedia) {
            val mimeType = wire.mimeType ?: ""
            val filename = wire.filename ?: ""
            var bitmap: ImageBitmap? = null
            var audioBytes: ByteArray? = null
            var audioDurationMs = 0L
            var isAudio = false

            if (mimeType.startsWith("image/")) {
                bitmap = runCatching {
                    decodeSampledBitmap(WireFormat.decodeMediaData(wire))
                }.getOrNull()
            } else if (mimeType.startsWith("audio/")) {
                isAudio = true
                runCatching {
                    val raw = WireFormat.decodeMediaData(wire)
                    audioBytes = raw
                    audioDurationMs = extractAudioDurationMs(raw)
                }
            }
            return DecodedMediaPreview(
                isMedia = true,
                isAudio = isAudio,
                mimeType = mimeType,
                filename = filename,
                bitmap = bitmap,
                audioBytes = audioBytes,
                audioDurationMs = audioDurationMs
            )
        }

        return DecodedMediaPreview(displayText = wire?.text ?: String(rawPlainBytes, Charsets.UTF_8))
    }

    private fun decodeSampledBitmap(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = GhostKeyUtils.calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_BUBBLE_IMAGE_EDGE)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    private fun extractAudioDurationMs(bytes: ByteArray): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(MemoryAudioDataSource(bytes))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    suspend fun sendText(
        target: ChatTarget,
        text: String,
        myKeys: PgpKeyPairArmored,
        userAlias: String,
        lanMesh: LocalLanMesh?
    ): Result<Unit> = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext Result.success(Unit)
        val ttl = when (target) {
            is ChatTarget.Direct -> target.contact.disappearingTtlSeconds
            is ChatTarget.Group -> null
        }
        val groupId = (target as? ChatTarget.Group)?.group?.id
        val payload = WireFormat.buildText(text, senderAlias = userAlias, groupId = groupId, ttlSeconds = ttl)
        sendBuiltPayload(
            target = target,
            payload = payload,
            myKeys = myKeys,
            userAlias = userAlias,
            lanMesh = lanMesh,
            displayText = text
        )
    }

    suspend fun sendMediaUri(
        target: ChatTarget,
        uri: Uri,
        myKeys: PgpKeyPairArmored,
        userAlias: String,
        lanMesh: LocalLanMesh?
    ): Result<Unit> {
        val loaded = withContext(Dispatchers.IO) {
            val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_ATTACHMENT_BYTES) }
                ?: return@withContext Result.failure<Triple<String, String, ByteArray>>(
                    IllegalArgumentException("File exceeds 15 MB limit")
                )
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val name = queryFilename(uri) ?: "attachment"
            Result.success(Triple(mime, name, bytes))
        }
        val (mime, name, bytes) = loaded.getOrElse { return Result.failure(it) }

        return withContext(Dispatchers.Default) {
            val isAudio = mime.startsWith("audio/")
            val bitmap = if (mime.startsWith("image/")) runCatching { decodeSampledBitmap(bytes) }.getOrNull() else null
            val audioDuration = if (isAudio) runCatching { extractAudioDurationMs(bytes) }.getOrDefault(0L) else 0L
            val ttl = (target as? ChatTarget.Direct)?.contact?.disappearingTtlSeconds
            val groupId = (target as? ChatTarget.Group)?.group?.id
            val payload = WireFormat.buildMedia(mime, name, bytes, senderAlias = userAlias, groupId = groupId, ttlSeconds = ttl)
            sendBuiltPayload(
                target = target,
                payload = payload,
                myKeys = myKeys,
                userAlias = userAlias,
                lanMesh = lanMesh,
                isMedia = true,
                isAudio = isAudio,
                mimeType = mime,
                filename = name,
                mediaBitmap = bitmap,
                audioBytes = if (isAudio) bytes else null,
                audioDurationMs = audioDuration
            )
        }
    }

    suspend fun sendAudioNote(
        target: ChatTarget,
        result: AudioRecordResult,
        myKeys: PgpKeyPairArmored,
        userAlias: String,
        lanMesh: LocalLanMesh?
    ): Result<Unit> = withContext(Dispatchers.Default) {
        val ttl = (target as? ChatTarget.Direct)?.contact?.disappearingTtlSeconds
        val groupId = (target as? ChatTarget.Group)?.group?.id
        val payload = WireFormat.buildMedia(
            result.mimeType,
            result.filename,
            result.bytes,
            senderAlias = userAlias,
            groupId = groupId,
            ttlSeconds = ttl
        )
        sendBuiltPayload(
            target = target,
            payload = payload,
            myKeys = myKeys,
            userAlias = userAlias,
            lanMesh = lanMesh,
            isMedia = true,
            isAudio = true,
            mimeType = result.mimeType,
            filename = result.filename,
            audioBytes = result.bytes,
            audioDurationMs = result.durationMs
        )
    }

    private suspend fun sendBuiltPayload(
        target: ChatTarget,
        payload: ByteArray,
        myKeys: PgpKeyPairArmored,
        userAlias: String,
        lanMesh: LocalLanMesh?,
        isMedia: Boolean = false,
        isAudio: Boolean = false,
        displayText: String? = null,
        mimeType: String = "",
        filename: String = "",
        mediaBitmap: ImageBitmap? = null,
        audioBytes: ByteArray? = null,
        audioDurationMs: Long = 0L
    ): Result<Unit> {
        val sendResult: Result<Unit> = when (target) {
            is ChatTarget.Group -> {
                val ciphertext = GroupCrypto.encrypt(payload, target.group.sharedKeyBase64)
                rememberSentCiphertext(ciphertext)
                torClient.sendMessage(dropId = target.group.groupDropId, ciphertext = ciphertext).map { }
            }
            is ChatTarget.Direct -> {
                val ciphertext = StandalonePgpEngine.encrypt(payload, target.contact.publicKeyArmored, myKeys.privateKeyArmored)
                val deliveredLan = lanMesh?.sendDirect(target.contact.dropId, ciphertext) == true
                if (deliveredLan) {
                    Result.success(Unit)
                } else {
                    torClient.sendMessage(dropId = target.contact.dropId, ciphertext = ciphertext).map { }
                }
            }
        }

        return sendResult.onSuccess {
            val wire = WireFormat.parse(payload)
            val ttl = wire?.ttlSeconds
            val expires = if (ttl != null && ttl > 0) System.currentTimeMillis() + (ttl * 1000L) else null
            val chatId = when (target) {
                is ChatTarget.Direct -> target.contact.dropId
                is ChatTarget.Group -> target.group.id
            }
            val msg = ChatMessage(
                id = UUID.randomUUID().toString(),
                isOutgoing = true,
                plaintext = if (isMedia) null else (displayText ?: wire?.text ?: String(payload, Charsets.UTF_8)),
                ciphertext = "",
                timestamp = System.currentTimeMillis(),
                isDecrypted = true,
                isMedia = isMedia,
                isAudio = isAudio,
                mimeType = mimeType,
                filename = filename,
                mediaBitmap = mediaBitmap,
                audioBytes = audioBytes,
                audioDurationMs = audioDurationMs,
                chatId = chatId,
                groupId = (target as? ChatTarget.Group)?.group?.id,
                isGroup = target is ChatTarget.Group,
                senderAlias = userAlias,
                expiresAt = expires,
                ttlSeconds = ttl
            )
            _messages.update { it + msg }
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
                    torClient.sendMessage(dropId = member.dropId, ciphertext = ciphertext)
                }
            }
        }

    suspend fun sendContactIntroduction(
        targetContact: Contact,
        contactToIntroduce: Contact,
        myKeys: PgpKeyPairArmored,
        userAlias: String,
        lanMesh: LocalLanMesh?
    ): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            val wireBytes = WireFormat.buildIntroduction(
                introAlias = contactToIntroduce.alias,
                introDropId = contactToIntroduce.dropId,
                introArmoredKey = contactToIntroduce.publicKeyArmored,
                introSafetyCode = contactToIntroduce.safetyCode,
                senderAlias = userAlias
            )
            val encryptedArmor = StandalonePgpEngine.encrypt(
                wireBytes,
                targetContact.publicKeyArmored,
                myKeys.privateKeyArmored
            )
            val outMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                isOutgoing = true,
                plaintext = "🤝 Introduced ${contactToIntroduce.alias} to ${targetContact.alias}",
                ciphertext = "",
                timestamp = System.currentTimeMillis(),
                isDecrypted = true,
                chatId = targetContact.dropId,
                isIntroduction = true,
                introAlias = contactToIntroduce.alias,
                introDropId = contactToIntroduce.dropId,
                introArmoredKey = contactToIntroduce.publicKeyArmored,
                introSafetyCode = contactToIntroduce.safetyCode
            )
            _messages.update { it + outMsg }
            val deliveredLan = lanMesh?.sendDirect(targetContact.dropId, encryptedArmor) == true
            if (!deliveredLan) {
                torClient.sendMessage(dropId = targetContact.dropId, ciphertext = encryptedArmor).getOrThrow()
            }
            Unit
        }
    }

    fun formatFriendlySendError(err: Throwable): String {
        val reason = when ((err as? RelayHttpException)?.code) {
            429 -> "rate limit reached, try again shortly"
            503 -> "the relay is at capacity"
            413 -> "message is too large for the relay"
            else -> err.message
        }
        return "Send failed: $reason"
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
                    lastMessageText = summaryPreview(lastMsg, "Group session ready"),
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
                    lastMessageText = summaryPreview(lastMsg, "Direct E2EE session"),
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
                    lastMessageText = summaryPreview(lastMsg, "Incoming message"),
                    lastMessageTime = lastMsg.timestamp,
                    avatarLetter = sender.take(1).uppercase()
                )
            )
        }

        return list.sortedByDescending { it.lastMessageTime }
    }

    private fun summaryPreview(msg: ChatMessage?, fallback: String): String =
        msg?.plaintext ?: when {
            msg?.isAudio == true -> "🎙️ Voice note"
            msg?.isMedia == true -> "📎 Attachment"
            else -> fallback
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
}
