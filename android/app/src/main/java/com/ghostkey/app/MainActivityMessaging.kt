package com.ghostkey.app

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ghostkey.app.audio.AudioRecordResult
import com.ghostkey.app.audio.MemoryAudioDataSource
import com.ghostkey.app.crypto.GroupCrypto
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.crypto.WireFormat
import com.ghostkey.app.data.ChatMessage
import com.ghostkey.app.data.Contact
import com.ghostkey.app.data.GroupChat
import com.ghostkey.app.net.EncryptedMessageRecord
import com.ghostkey.app.net.RelayHttpException
import com.ghostkey.app.notify.PushNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

internal fun MainActivity.handleIncoming(record: EncryptedMessageRecord) {
    if (sentCiphertexts.contains(record.ciphertext)) {
        // Echo of our own outgoing message on shared groupDropId
        torClient.ack(listOf(record.id), dropId = record.dropId)
        return
    }
    val existing = messagesState.firstOrNull { it.id == record.id }
    if (existing != null) {
        if (existing.isDecrypted) torClient.ack(listOf(existing.id), dropId = record.dropId)
        return
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
    messagesState.add(msg)
    decryptMessage(msg, record.dropId)
}

internal fun MainActivity.sendMedia(uri: Uri, targetChatId: String = "") {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it, maxAttachmentBytes) }
            if (bytes == null) {
                runOnUiThread {
                    Toast.makeText(applicationContext, "File exceeds 15 MB limit", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            val name = queryFilename(uri) ?: "file"
            var preview: ImageBitmap? = null
            if (mime.startsWith("image/")) {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                val opt = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(b.outWidth, b.outHeight, MAX_BUBBLE_IMAGE_EDGE)
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)?.let {
                    preview = it.asImageBitmap()
                }
            }
            val payload = WireFormat.buildMedia(mime, name, bytes, senderAlias = userAlias)
            runOnUiThread {
                sendPayload(
                    payload = payload,
                    isMedia = true,
                    mimeType = mime,
                    filename = name,
                    mediaBitmap = preview,
                    targetChatId = targetChatId
                )
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(applicationContext, "Could not read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

internal fun MainActivity.queryFilename(uri: Uri): String? {
    val cursor = contentResolver.query(uri, null, null, null, null) ?: return null
    cursor.use {
        val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
    }
    return null
}

internal fun MainActivity.readLimited(input: InputStream, maxBytes: Int): ByteArray? {
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

internal fun MainActivity.sendMessage(text: String, targetChatId: String = "") {
    if (text.isBlank()) return
    val finalChatId = targetChatId.ifBlank { recipientDropId }
    val ttl = getDisappearingTtlForChat(finalChatId)
    sendPayload(
        payload = WireFormat.buildText(text, senderAlias = userAlias, ttlSeconds = ttl),
        isMedia = false,
        displayText = text,
        targetChatId = targetChatId
    )
}

internal fun MainActivity.sendAudioRecordResult(result: AudioRecordResult, targetChatId: String = "") {
    val finalChatId = targetChatId.ifBlank { recipientDropId }
    val ttl = getDisappearingTtlForChat(finalChatId)
    val payload = WireFormat.buildMedia(result.mimeType, result.filename, result.bytes, senderAlias = userAlias, ttlSeconds = ttl)
    sendPayload(
        payload = payload,
        isMedia = true,
        isAudio = true,
        mimeType = result.mimeType,
        filename = result.filename,
        audioBytes = result.bytes,
        audioDurationMs = result.durationMs,
        targetChatId = targetChatId
    )
}

internal fun MainActivity.sendPayload(
    payload: ByteArray,
    isMedia: Boolean = false,
    isAudio: Boolean = false,
    displayText: String? = null,
    mimeType: String = "",
    filename: String = "",
    mediaBitmap: ImageBitmap? = null,
    audioBytes: ByteArray? = null,
    audioDurationMs: Long = 0L,
    targetChatId: String = ""
) {
    val keys = myPgpKeys
    if (keys == null) {
        Toast.makeText(applicationContext, "No active PGP key in RAM. Please generate keys first.", Toast.LENGTH_SHORT).show()
        return
    }

    if (recipientArmoredKey.isBlank()) {
        Toast.makeText(applicationContext, "Please select or paste your peer's public key first!", Toast.LENGTH_LONG).show()
        return
    }

    if (recipientDropId.isBlank()) {
        Toast.makeText(
            applicationContext,
            "Peer key has no Drop-ID.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val peerKey = recipientArmoredKey
    val peerDrop = recipientDropId
    val finalChatId = targetChatId.ifBlank { peerDrop }

    CoroutineScope(Dispatchers.Default).launch {
        try {
            val ciphertext = StandalonePgpEngine.encrypt(payload, peerKey, keys.privateKeyArmored)
            // Attempt Local LAN direct mesh delivery first (offline capability)
            val sentViaLan = localLanMesh?.sendDirect(peerDrop, ciphertext) == true
            val result = if (sentViaLan) {
                Result.success(Unit)
            } else {
                torClient.sendMessage(dropId = peerDrop, ciphertext = ciphertext)
            }
            result.onSuccess {
                runOnUiThread {
                    val cleanText = if (isMedia) null else (displayText ?: WireFormat.parse(payload)?.text ?: String(payload, Charsets.UTF_8))
                    val wire = WireFormat.parse(payload)
                    val ttl = wire?.ttlSeconds
                    val expires = if (ttl != null && ttl > 0) System.currentTimeMillis() + (ttl * 1000L) else null
                    val msg = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        isOutgoing = true,
                        plaintext = cleanText,
                        ciphertext = ciphertext,
                        timestamp = System.currentTimeMillis(),
                        isDecrypted = true,
                        isMedia = isMedia,
                        isAudio = isAudio,
                        mimeType = mimeType,
                        filename = filename,
                        mediaBitmap = mediaBitmap,
                        audioBytes = audioBytes,
                        audioDurationMs = audioDurationMs,
                        chatId = finalChatId,
                        senderAlias = userAlias,
                        expiresAt = expires,
                        ttlSeconds = ttl
                    )
                    messagesState.add(msg)
                }
            }.onFailure { err ->
                runOnUiThread {
                    val reason = when ((err as? RelayHttpException)?.code) {
                        429 -> "rate limit reached, try again shortly"
                        503 -> "the relay is at capacity"
                        413 -> "message is too large for the relay"
                        else -> err.message
                    }
                    Toast.makeText(applicationContext, "Send failed: $reason", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(applicationContext, "Encryption error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.decryptMessage(msg: ChatMessage, dropId: String) {
    val keys = myPgpKeys ?: return

    // Check if message belongs to a known group
    val group = groups.find { it.groupDropId == dropId || it.id == dropId }
    if (group != null) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val plainBytes = GroupCrypto.decrypt(msg.ciphertext, group.sharedKeyBase64)
                val wire = WireFormat.parse(plainBytes)

                // Anti-replay check for group messages
                if (wire != null && wire.nonce != null) {
                    synchronized(seenNonces) {
                        if (seenNonces.contains(wire.nonce)) {
                            torClient.ack(listOf(msg.id), dropId = group.groupDropId)
                            return@launch
                        }
                        seenNonces.add(wire.nonce)
                        if (seenNonces.size > 2000) {
                            val first = seenNonces.iterator().next()
                            seenNonces.remove(first)
                        }
                    }
                }

                var isMedia = false
                var isAudio = false
                var mimeType = ""
                var filename = ""
                var previewBitmap: ImageBitmap? = null
                var audioBytes: ByteArray? = null
                var audioDurationMs = 0L
                var displayText: String? = null

                var isIntro = false
            var introAlias: String? = null
            var introDropId: String? = null
            var introArmoredKey: String? = null
            var introSafetyCode: String? = null

            if (wire != null && wire.isIntroduction) {
                isIntro = true
                introAlias = wire.introAlias
                introDropId = wire.introDropId
                introArmoredKey = wire.introArmoredKey
                introSafetyCode = wire.introSafetyCode
                displayText = "🤝 Introduced contact: ${wire.introAlias}"
            } else if (wire != null && wire.isMedia) {
                    isMedia = true
                    mimeType = wire.mimeType ?: ""
                    filename = wire.filename ?: ""
                    if (mimeType.startsWith("image/")) {
                        try {
                            val mediaBytes = WireFormat.decodeMediaData(wire)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(mediaBytes, 0, mediaBytes.size, bounds)
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_BUBBLE_IMAGE_EDGE)
                            }
                            BitmapFactory.decodeByteArray(mediaBytes, 0, mediaBytes.size, options)?.let {
                                previewBitmap = it.asImageBitmap()
                            }
                        } catch (_: Throwable) {}
                    } else if (mimeType.startsWith("audio/")) {
                        isAudio = true
                        try {
                            val rawBytes = WireFormat.decodeMediaData(wire)
                            audioBytes = rawBytes
                            val retriever = MediaMetadataRetriever()
                            val ds = MemoryAudioDataSource(rawBytes)
                            retriever.setDataSource(ds)
                            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            audioDurationMs = durStr?.toLongOrNull() ?: 0L
                            retriever.release()
                        } catch (_: Throwable) {}
                    }
                } else {
                    displayText = wire?.text ?: String(plainBytes, Charsets.UTF_8)
                }

                val wireTtl = wire?.ttlSeconds
                val expires = if (wireTtl != null && wireTtl > 0) System.currentTimeMillis() + (wireTtl * 1000L) else null

                runOnUiThread {
                    val index = messagesState.indexOfFirst { it.id == msg.id }
                    if (index != -1) {
                        messagesState[index] = messagesState[index].copy(
                            plaintext = displayText,
                            isDecrypted = true,
                            isMedia = isMedia,
                            isAudio = isAudio,
                            mimeType = mimeType,
                            filename = filename,
                            mediaBitmap = previewBitmap,
                            audioBytes = audioBytes,
                            audioDurationMs = audioDurationMs,
                            chatId = group.id,
                            groupId = group.id,
                            isGroup = true,
                            senderAlias = wire?.senderAlias,
                            expiresAt = expires,
                            ttlSeconds = wireTtl,
                            isIntroduction = isIntro,
                            introAlias = introAlias,
                            introDropId = introDropId,
                            introArmoredKey = introArmoredKey,
                            introSafetyCode = introSafetyCode
                        )
                        torClient.ack(listOf(msg.id), dropId = group.groupDropId)
                    }
                }

                if (!isForeground) {
                    PushNotificationManager.postMessageNotification(
                        context = applicationContext,
                        isAudio = isAudio,
                        hasMedia = isMedia,
                        previewText = if (!isAudio && !isMedia) displayText else null
                    )
                }
            } catch (_: Exception) {}
        }
        return
    }

    // Direct 1-to-1 PGP message
    CoroutineScope(Dispatchers.Default).launch {
        try {
            val candidateKeys = (contacts.mapNotNull { it.publicKeyArmored.ifBlank { null } } +
                listOfNotNull(recipientArmoredKey.ifBlank { null })).distinct()
            val result = StandalonePgpEngine.decryptMulti(msg.ciphertext, keys.privateKeyArmored, candidateKeys)
            val wire = WireFormat.parse(result.bytes)

            // Handle Group Invites
            if (wire != null && wire.isGroupInvite && wire.groupId != null && wire.groupDropId != null) {
                val newGroup = GroupChat(
                    id = wire.groupId,
                    name = wire.groupName ?: "Encrypted Group",
                    groupDropId = wire.groupDropId,
                    groupReadToken = wire.groupReadToken ?: "",
                    sharedKeyBase64 = wire.sharedKey ?: "",
                    members = emptyList()
                )
                if (!groups.any { it.id == newGroup.id }) {
                    runOnUiThread {
                        groups.add(newGroup)
                    }
                    torClient.addSubscription(newGroup.groupDropId, newGroup.groupReadToken)
                    persistIdentity()
                    runOnUiThread {
                        Toast.makeText(applicationContext, "Invited to group: ${newGroup.name} 👥", Toast.LENGTH_LONG).show()
                    }
                }
            }

            // Anti-replay check
            if (wire != null && wire.nonce != null) {
                synchronized(seenNonces) {
                    if (seenNonces.contains(wire.nonce)) {
                        torClient.ack(listOf(msg.id), dropId = dropId)
                        return@launch
                    }
                    seenNonces.add(wire.nonce)
                    if (seenNonces.size > 2000) {
                        val first = seenNonces.iterator().next()
                        seenNonces.remove(first)
                    }
                }
            }

            var isMedia = false
            var isAudio = false
            var mimeType = ""
            var filename = ""
            var previewBitmap: ImageBitmap? = null
            var audioBytes: ByteArray? = null
            var audioDurationMs = 0L
            var displayText: String? = null

            var isIntro = false
            var introAlias: String? = null
            var introDropId: String? = null
            var introArmoredKey: String? = null
            var introSafetyCode: String? = null

            if (wire != null && wire.isGroupInvite) {
                val groupName = wire.groupName ?: "New Group"
                displayText = "Group invitation: $groupName"
                if (!wire.groupId.isNullOrBlank() && !wire.sharedKey.isNullOrBlank() && !wire.groupDropId.isNullOrBlank() && !wire.groupReadToken.isNullOrBlank()) {
                    val newGroup = GroupChat(
                        id = wire.groupId,
                        name = groupName,
                        groupDropId = wire.groupDropId,
                        groupReadToken = wire.groupReadToken,
                        sharedKeyBase64 = wire.sharedKey,
                        members = emptyList(),
                        createdAt = System.currentTimeMillis()
                    )
                    if (!groups.any { it.id == newGroup.id || it.groupDropId == newGroup.groupDropId }) {
                        runOnUiThread {
                            groups.add(newGroup)
                            torClient.addSubscription(newGroup.groupDropId, newGroup.groupReadToken)
                            persistIdentity()
                        }
                    }
                }
            } else if (wire != null && wire.isIntroduction) {
                isIntro = true
                introAlias = wire.introAlias
                introDropId = wire.introDropId
                introArmoredKey = wire.introArmoredKey
                introSafetyCode = wire.introSafetyCode
                displayText = "🤝 Introduced contact: ${wire.introAlias}"
            } else if (wire != null && wire.isMedia) {
                isMedia = true
                mimeType = wire.mimeType ?: ""
                filename = wire.filename ?: ""
                if (mimeType.startsWith("image/")) {
                    try {
                        val mediaBytes = WireFormat.decodeMediaData(wire)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(mediaBytes, 0, mediaBytes.size, bounds)
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_BUBBLE_IMAGE_EDGE)
                        }
                        BitmapFactory.decodeByteArray(mediaBytes, 0, mediaBytes.size, options)?.let {
                            previewBitmap = it.asImageBitmap()
                        }
                    } catch (_: Throwable) {}
                } else if (mimeType.startsWith("audio/")) {
                    isAudio = true
                    try {
                        val rawBytes = WireFormat.decodeMediaData(wire)
                        audioBytes = rawBytes
                        val retriever = MediaMetadataRetriever()
                        val ds = MemoryAudioDataSource(rawBytes)
                        retriever.setDataSource(ds)
                        val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        audioDurationMs = durStr?.toLongOrNull() ?: 0L
                        retriever.release()
                    } catch (_: Throwable) {}
                }
            } else {
                displayText = wire?.text ?: String(result.bytes, Charsets.UTF_8)
            }

            val senderFp = result.signerFingerprint
            val matchedContact = contacts.find { it.fingerprint == senderFp }
            val targetChat = matchedContact?.dropId ?: dropId
            val wireTtl = wire?.ttlSeconds
            val expires = if (wireTtl != null && wireTtl > 0) System.currentTimeMillis() + (wireTtl * 1000L) else null

            runOnUiThread {
                val index = messagesState.indexOfFirst { it.id == msg.id }
                if (index != -1) {
                    messagesState[index] = messagesState[index].copy(
                        plaintext = displayText,
                        isDecrypted = true,
                        isMedia = isMedia,
                        isAudio = isAudio,
                        mimeType = mimeType,
                        filename = filename,
                        mediaBitmap = previewBitmap,
                        audioBytes = audioBytes,
                        audioDurationMs = audioDurationMs,
                        signatureStatus = result.signatureStatus,
                        signerFingerprint = result.signerFingerprint,
                        chatId = targetChat,
                        senderAlias = wire?.senderAlias ?: matchedContact?.alias,
                        expiresAt = expires,
                        ttlSeconds = wireTtl,
                        isIntroduction = isIntro,
                        introAlias = introAlias,
                        introDropId = introDropId,
                        introArmoredKey = introArmoredKey,
                        introSafetyCode = introSafetyCode
                    )
                    torClient.ack(listOf(msg.id), dropId = dropId)
                }
                if (!isForeground) {
                    PushNotificationManager.postMessageNotification(
                        context = applicationContext,
                        isAudio = isAudio,
                        hasMedia = isMedia,
                        previewText = if (!isAudio && !isMedia) displayText else null
                    )
                }
            }
        } catch (_: Exception) {
            if (!isForeground) {
                runOnUiThread {
                    PushNotificationManager.postMessageNotification(
                        context = applicationContext,
                        isAudio = false,
                        hasMedia = false,
                        previewText = null
                    )
                }
            }
        }
    }
}

internal fun MainActivity.sendMessageToActiveChat(text: String) {
    val group = activeChatGroup
    val contact = activeChatContact
    if (group != null) {
        sendGroupTextMessage(group, text)
    } else if (contact != null) {
        recipientArmoredKey = contact.publicKeyArmored
        recipientFingerprint = contact.fingerprint
        recipientDropId = contact.dropId
        sendMessage(text, targetChatId = contact.dropId)
    } else {
        sendMessage(text)
    }
}

internal fun MainActivity.sendMediaToActiveChat(uri: Uri) {
    val group = activeChatGroup
    val contact = activeChatContact
    if (group != null) {
        sendGroupMedia(group, uri)
    } else if (contact != null) {
        recipientArmoredKey = contact.publicKeyArmored
        recipientFingerprint = contact.fingerprint
        recipientDropId = contact.dropId
        sendMedia(uri, targetChatId = contact.dropId)
    } else {
        sendMedia(uri)
    }
}

internal fun MainActivity.sendGroupTextMessage(group: GroupChat, text: String) {
    val payload = WireFormat.buildText(text, senderAlias = userAlias, groupId = group.id)
    val ciphertext = GroupCrypto.encrypt(payload, group.sharedKeyBase64)
    sentCiphertexts.add(ciphertext)
    CoroutineScope(Dispatchers.Default).launch {
        try {
            val res = torClient.sendMessage(dropId = group.groupDropId, ciphertext = ciphertext)
            res.onSuccess {
                runOnUiThread {
                    val msg = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        isOutgoing = true,
                        plaintext = text,
                        ciphertext = ciphertext,
                        timestamp = System.currentTimeMillis(),
                        isDecrypted = true,
                        chatId = group.id,
                        groupId = group.id,
                        isGroup = true,
                        senderAlias = userAlias
                    )
                    messagesState.add(msg)
                }
            }.onFailure { err ->
                runOnUiThread {
                    Toast.makeText(applicationContext, "Failed to send to group: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(applicationContext, "Group encrypt error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.sendGroupMedia(group: GroupChat, uri: Uri) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val cr = contentResolver
            val mimeType = cr.getType(uri) ?: "application/octet-stream"
            val filename = queryFilename(uri) ?: "attachment"
            val rawBytes = contentResolver.openInputStream(uri)?.use { readLimited(it, maxAttachmentBytes) } ?: return@launch
            val isAudio = mimeType.startsWith("audio/")
            val payload = WireFormat.buildMedia(mimeType, filename, rawBytes, senderAlias = userAlias, groupId = group.id)
            val ciphertext = GroupCrypto.encrypt(payload, group.sharedKeyBase64)
            sentCiphertexts.add(ciphertext)

            val res = torClient.sendMessage(dropId = group.groupDropId, ciphertext = ciphertext)
            res.onSuccess {
                runOnUiThread {
                    val msg = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        isOutgoing = true,
                        ciphertext = ciphertext,
                        timestamp = System.currentTimeMillis(),
                        isDecrypted = true,
                        isMedia = true,
                        isAudio = isAudio,
                        mimeType = mimeType,
                        filename = filename,
                        audioBytes = if (isAudio) rawBytes else null,
                        chatId = group.id,
                        groupId = group.id,
                        isGroup = true,
                        senderAlias = userAlias
                    )
                    messagesState.add(msg)
                }
            }.onFailure { err ->
                runOnUiThread {
                    Toast.makeText(applicationContext, "Failed to send group media: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(applicationContext, "Media error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.inviteMembersToGroup(group: GroupChat) {
    val keys = myPgpKeys ?: return
    for (member in group.members) {
        val invitePayload = WireFormat.buildGroupInvite(
            groupId = group.id,
            groupName = group.name,
            groupDropId = group.groupDropId,
            groupReadToken = group.groupReadToken,
            sharedKey = group.sharedKeyBase64,
            senderAlias = userAlias
        )
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val ciphertext = StandalonePgpEngine.encrypt(invitePayload, member.publicKeyArmored, keys.privateKeyArmored)
                torClient.sendMessage(dropId = member.dropId, ciphertext = ciphertext)
            } catch (_: Exception) {}
        }
    }
}

internal fun MainActivity.sendContactIntroduction(targetContact: Contact, contactToIntroduce: Contact) {
    val keys = myPgpKeys ?: return
    CoroutineScope(Dispatchers.Default).launch {
        try {
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
                keys.privateKeyArmored
            )
            val msgId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val outMsg = ChatMessage(
                id = msgId,
                isOutgoing = true,
                plaintext = "🤝 Introduced ${contactToIntroduce.alias} to ${targetContact.alias}",
                ciphertext = encryptedArmor,
                timestamp = now,
                isDecrypted = true,
                chatId = targetContact.dropId,
                isIntroduction = true,
                introAlias = contactToIntroduce.alias,
                introDropId = contactToIntroduce.dropId,
                introArmoredKey = contactToIntroduce.publicKeyArmored,
                introSafetyCode = contactToIntroduce.safetyCode
            )
            runOnUiThread {
                messagesState.add(outMsg)
            }
            val deliveredLan = localLanMesh?.sendDirect(targetContact.dropId, encryptedArmor) == true
            if (!deliveredLan) {
                torClient.sendMessage(dropId = targetContact.dropId, ciphertext = encryptedArmor)
            }
            runOnUiThread {
                Toast.makeText(applicationContext, "Introduction sent to ${targetContact.alias} 🤝", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(applicationContext, "Failed to introduce: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun MainActivity.addIntroducedContact(alias: String, dropId: String, armoredKey: String, safetyCode: String?) {
    val cleanDropId = dropId.trim().lowercase()
    if (!StandalonePgpEngine.isValidHex32(cleanDropId)) {
        Toast.makeText(applicationContext, "Invalid Drop-ID in contact introduction", Toast.LENGTH_SHORT).show()
        return
    }
    val info = try { StandalonePgpEngine.parsePeerKey(armoredKey) } catch (_: Exception) { null }
    if (info == null) {
        Toast.makeText(applicationContext, "Invalid PGP key in contact introduction", Toast.LENGTH_SHORT).show()
        return
    }
    if (info.dropId != null && info.dropId != cleanDropId) {
        Toast.makeText(applicationContext, "Security warning: Introduced Drop-ID does not match key!", Toast.LENGTH_LONG).show()
        return
    }
    if (contacts.any { it.dropId == cleanDropId || it.fingerprint == info.fingerprint }) {
        Toast.makeText(applicationContext, "Contact already exists in your address book", Toast.LENGTH_SHORT).show()
        return
    }
    val cleanAlias = alias.trim().replace(Regex("[\\r\\n\\t\\u202A-\\u202E\\u200E\\u200F<>]"), "").take(64)
    val verifiedSas = myPgpKeys?.let { StandalonePgpEngine.generateSafetyCode(it.fingerprint, info.fingerprint) }
    val isVerified = safetyCode != null && verifiedSas != null && safetyCode.replace(" ", "") == verifiedSas.replace(" ", "")
    val newContact = Contact(
        id = cleanDropId,
        alias = if (cleanAlias.isNotBlank()) cleanAlias else info.alias ?: "Peer",
        dropId = cleanDropId,
        publicKeyArmored = info.publicKeyArmored,
        fingerprint = info.fingerprint,
        isVerified = isVerified,
        safetyCode = verifiedSas
    )
    contacts.add(newContact)
    persistIdentity()
    Toast.makeText(applicationContext, "Added ${newContact.alias} to contacts 🛡️", Toast.LENGTH_SHORT).show()
}
