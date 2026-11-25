package com.ghostkey.app.net.mesh

import com.ghostkey.app.crypto.StandalonePgpEngine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Bitchat-hardened binary multi-hop mesh packet with:
 * - Ephemeral Ed25519 packet signature (excluding mutable TTL) verified BEFORE dedupe
 * - 5-minute replay window
 * - Bitchat-style FragmentManager bounds (global buffered bytes cap + overwrite protection)
 * - Adaptive relay probability for congestion control
 */
data class MeshPacket(
    val version: Byte = PROTOCOL_VERSION,
    val flags: Byte = 0,
    val ttlHops: Int = DEFAULT_TTL_HOPS,
    val packetId: ByteArray,
    val originTag: ByteArray,
    val recipientTag: ByteArray,
    val timestampMs: Long = System.currentTimeMillis(),
    val signerPubKeyX509: ByteArray = ByteArray(0),
    val signature: ByteArray = ByteArray(0),
    val payload: ByteArray
) {
    companion object {
        const val PROTOCOL_VERSION: Byte = 2
        const val DEFAULT_TTL_HOPS = 5
        const val MAX_TTL_HOPS = 7
        const val TAG_LEN = 8
        const val PACKET_ID_LEN = 8
        const val REPLAY_WINDOW_MS = 5 * 60_000L
        const val MAX_PAYLOAD_BYTES = 512 * 1024
        private const val MAX_PUBKEY_BYTES = 128
        private const val MAX_SIG_BYTES = 128
        private const val FIXED_HEADER_LEN = 1 + 1 + 1 + PACKET_ID_LEN + TAG_LEN + TAG_LEN + 8 + 2 + 2 + 4

        private val random = SecureRandom()

        fun randomBytes(len: Int): ByteArray = ByteArray(len).also { random.nextBytes(it) }

        fun generateEphemeralSigningKeyPair(): KeyPair {
            val kpg = KeyPairGenerator.getInstance("Ed25519", StandalonePgpEngine.bcProvider)
            return kpg.generateKeyPair()
        }

        fun deriveTag(dropId: String?): ByteArray {
            if (dropId.isNullOrBlank()) return ByteArray(TAG_LEN)
            val digest = MessageDigest.getInstance("SHA-256").digest(dropId.trim().lowercase().toByteArray(Charsets.UTF_8))
            return digest.copyOf(TAG_LEN)
        }

        fun decode(raw: ByteArray): MeshPacket? {
            if (raw.size < FIXED_HEADER_LEN) return null
            val buf = ByteBuffer.wrap(raw)
            val version = buf.get()
            if (version != PROTOCOL_VERSION) return null
            val flags = buf.get()
            val ttl = buf.get().toInt() and 0xFF
            val packetId = ByteArray(PACKET_ID_LEN).also { buf.get(it) }
            val originTag = ByteArray(TAG_LEN).also { buf.get(it) }
            val recipientTag = ByteArray(TAG_LEN).also { buf.get(it) }
            val ts = buf.getLong()
            val pubLen = buf.getShort().toInt() and 0xFFFF
            val sigLen = buf.getShort().toInt() and 0xFFFF
            val payloadLen = buf.getInt()
            if (pubLen > MAX_PUBKEY_BYTES || sigLen > MAX_SIG_BYTES ||
                payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES ||
                buf.remaining() != pubLen + sigLen + payloadLen
            ) {
                return null
            }
            val pub = ByteArray(pubLen).also { buf.get(it) }
            val sig = ByteArray(sigLen).also { buf.get(it) }
            val payload = ByteArray(payloadLen).also { buf.get(it) }
            return MeshPacket(
                version = version,
                flags = flags,
                ttlHops = ttl,
                packetId = packetId,
                originTag = originTag,
                recipientTag = recipientTag,
                timestampMs = ts,
                signerPubKeyX509 = pub,
                signature = sig,
                payload = payload
            )
        }
    }

    /** Bytes covered by Ed25519 signature (TTL excluded so hops can decrement safely). */
    fun signingPreimage(): ByteArray {
        val out = ByteBuffer.allocate(1 + 1 + PACKET_ID_LEN + TAG_LEN + TAG_LEN + 8 + 4 + payload.size)
        out.put(version)
        out.put(flags)
        out.put(packetId.copyOf(PACKET_ID_LEN))
        out.put(originTag.copyOf(TAG_LEN))
        out.put(recipientTag.copyOf(TAG_LEN))
        out.putLong(timestampMs)
        out.putInt(payload.size)
        out.put(payload)
        return out.array()
    }

    fun signedWith(privateKey: PrivateKey, publicKey: PublicKey): MeshPacket {
        val sig = Signature.getInstance("Ed25519", StandalonePgpEngine.bcProvider)
        sig.initSign(privateKey)
        sig.update(signingPreimage())
        return copy(
            signerPubKeyX509 = publicKey.encoded,
            signature = sig.sign()
        )
    }

    fun verifySignature(): Boolean {
        if (signerPubKeyX509.isEmpty() || signature.isEmpty()) return false
        return runCatching {
            val kf = java.security.KeyFactory.getInstance("Ed25519", StandalonePgpEngine.bcProvider)
            val pub = kf.generatePublic(X509EncodedKeySpec(signerPubKeyX509))
            val sig = Signature.getInstance("Ed25519", StandalonePgpEngine.bcProvider)
            sig.initVerify(pub)
            sig.update(signingPreimage())
            sig.verify(signature)
        }.getOrDefault(false)
    }

    fun encode(): ByteArray {
        val out = ByteBuffer.allocate(FIXED_HEADER_LEN + signerPubKeyX509.size + signature.size + payload.size)
        out.put(version)
        out.put(flags)
        out.put(ttlHops.coerceIn(0, MAX_TTL_HOPS).toByte())
        out.put(packetId.copyOf(PACKET_ID_LEN))
        out.put(originTag.copyOf(TAG_LEN))
        out.put(recipientTag.copyOf(TAG_LEN))
        out.putLong(timestampMs)
        out.putShort(signerPubKeyX509.size.toShort())
        out.putShort(signature.size.toShort())
        out.putInt(payload.size)
        out.put(signerPubKeyX509)
        out.put(signature)
        out.put(payload)
        return out.array()
    }

    fun decrementedHop(): MeshPacket = copy(ttlHops = (ttlHops - 1).coerceAtLeast(0))

    fun packetIdHex(): String = packetId.joinToString("") { "%02x".format(it) }

    fun isBroadcast(): Boolean = recipientTag.all { it == 0.toByte() }
}

/**
 * Splits oversized [MeshPacket] frames into BLE ATT MTU chunks and reassembles them with
 * Bitchat-style global memory bounds, duplicate-overwrite protection, and metadata lock.
 */
class BleFragmenter(
    private val chunkDataSize: Int = DEFAULT_CHUNK_DATA_SIZE,
    private val reassemblyTimeoutMs: Long = 30_000L,
    private val maxActiveSets: Int = 24,
    private val maxFragmentsPerId: Int = 256,
    private val maxSetBytes: Int = 512 * 1024,
    private val maxGlobalBufferedBytes: Long = 2L * 1024L * 1024L
) {
    companion object {
        const val MAGIC_0: Byte = 0x47 // 'G'
        const val MAGIC_1: Byte = 0x4D // 'M'
        private const val CHUNK_HEADER_LEN = 10 // magic(2) + transferId(4) + index(2) + total(2)
        const val DEFAULT_CHUNK_DATA_SIZE = 180
    }

    private data class AssemblyBuffer(
        val totalChunks: Int,
        val chunks: Array<ByteArray?>,
        val createdAtMs: Long,
        var setBytes: Int = 0,
        var receivedCount: Int = 0
    )

    private val random = SecureRandom()
    private val stateLock = Any()
    private val inFlight = LinkedHashMap<Int, AssemblyBuffer>()
    private var globalBufferedBytes: Long = 0L

    fun fragment(packetBytes: ByteArray): List<ByteArray> {
        require(packetBytes.isNotEmpty()) { "Cannot fragment empty packet" }
        val totalChunks = (packetBytes.size + chunkDataSize - 1) / chunkDataSize
        require(totalChunks <= maxFragmentsPerId && totalChunks <= 65535) { "Packet too large for BLE fragmentation" }
        val transferId = random.nextInt()
        val result = ArrayList<ByteArray>(totalChunks)
        var offset = 0
        for (idx in 0 until totalChunks) {
            val end = minOf(packetBytes.size, offset + chunkDataSize)
            val sliceLen = end - offset
            val frame = ByteBuffer.allocate(CHUNK_HEADER_LEN + sliceLen)
            frame.put(MAGIC_0)
            frame.put(MAGIC_1)
            frame.putInt(transferId)
            frame.putShort(idx.toShort())
            frame.putShort(totalChunks.toShort())
            frame.put(packetBytes, offset, sliceLen)
            result.add(frame.array())
            offset = end
        }
        return result
    }

    fun onChunkReceived(chunkBytes: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray? {
        if (chunkBytes.size < CHUNK_HEADER_LEN) return null
        val buf = ByteBuffer.wrap(chunkBytes)
        if (buf.get() != MAGIC_0 || buf.get() != MAGIC_1) return null
        val transferId = buf.getInt()
        val idx = buf.getShort().toInt() and 0xFFFF
        val total = buf.getShort().toInt() and 0xFFFF
        if (total <= 0 || total > maxFragmentsPerId || idx >= total) return null

        val payload = ByteArray(buf.remaining()).also { buf.get(it) }

        synchronized(stateLock) {
            pruneStaleLocked(nowMs)

            val existing = inFlight[transferId]
            if (existing != null && existing.totalChunks != total) {
                // Reject spoofed conflicting metadata without destroying the valid assembly
                return null
            }
            val isNew = existing == null
            if (isNew && inFlight.size >= maxActiveSets) {
                return null
            }

            val assembly = existing ?: AssemblyBuffer(total, arrayOfNulls(total), nowMs).also {
                inFlight[transferId] = it
            }

            val held = assembly.chunks[idx]
            if (held != null) {
                // Reject conflicting duplicate bytes (anti-overwrite attack)
                return null
            }

            val newSetBytes = assembly.setBytes + payload.size
            if (newSetBytes > maxSetBytes || globalBufferedBytes + payload.size > maxGlobalBufferedBytes) {
                if (isNew) removeAssemblyLocked(transferId)
                return null
            }

            assembly.chunks[idx] = payload
            assembly.setBytes = newSetBytes
            assembly.receivedCount++
            globalBufferedBytes += payload.size

            if (assembly.receivedCount < total) return null

            removeAssemblyLocked(transferId)
            val out = ByteArrayOutputStream(newSetBytes)
            for (part in assembly.chunks) {
                out.write(part ?: return null)
            }
            return out.toByteArray()
        }
    }

    private fun removeAssemblyLocked(transferId: Int) {
        val removed = inFlight.remove(transferId) ?: return
        globalBufferedBytes = (globalBufferedBytes - removed.setBytes).coerceAtLeast(0L)
    }

    private fun pruneStaleLocked(nowMs: Long) {
        val it = inFlight.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (nowMs - entry.value.createdAtMs > reassemblyTimeoutMs) {
                globalBufferedBytes = (globalBufferedBytes - entry.value.setBytes).coerceAtLeast(0L)
                it.remove()
            }
        }
    }
}

data class RouteDecision(
    val packet: MeshPacket?,
    val deliverLocally: Boolean,
    val relayPacket: MeshPacket?
)

/**
 * Bitchat-hardened mesh router:
 * - Enforces replay window (5 min)
 * - Verifies ephemeral Ed25519 signature BEFORE recording packetId in dedupe cache (prevents cache poisoning)
 * - Decrements TTL and enforces adaptive relay probability
 * - Maintains Store-and-Forward buffer for disconnected/newly-arrived neighbors
 */
class MeshRouter(
    private val maxSeenPackets: Int = 1024,
    private val maxStoredForwardPackets: Int = 96,
    private val storeTtlMs: Long = 15 * 60_000L
) {
    private val seenIds = LinkedHashSet<String>()
    private val storeQueue = ArrayList<Pair<Long, MeshPacket>>()
    private val sessionOriginTag = MeshPacket.randomBytes(MeshPacket.TAG_LEN)
    private var signingKeyPair: KeyPair = MeshPacket.generateEphemeralSigningKeyPair()
    private val random = SecureRandom()

    val currentOriginTag: ByteArray get() = sessionOriginTag.copyOf()

    @Synchronized
    fun createOutgoingPacket(
        recipientDropId: String?,
        payloadBytes: ByteArray,
        ttlHops: Int = MeshPacket.DEFAULT_TTL_HOPS,
        nowMs: Long = System.currentTimeMillis()
    ): MeshPacket {
        val unsigned = MeshPacket(
            ttlHops = ttlHops.coerceIn(1, MeshPacket.MAX_TTL_HOPS),
            packetId = MeshPacket.randomBytes(MeshPacket.PACKET_ID_LEN),
            originTag = sessionOriginTag,
            recipientTag = MeshPacket.deriveTag(recipientDropId),
            timestampMs = nowMs,
            payload = payloadBytes
        )
        val signed = unsigned.signedWith(signingKeyPair.private, signingKeyPair.public)
        markSeen(signed.packetIdHex())
        enqueueForForward(signed, nowMs)
        return signed
    }

    @Synchronized
    fun onIncomingBytes(
        rawBytes: ByteArray,
        myDropId: String,
        peerCount: Int = 1,
        nowMs: Long = System.currentTimeMillis()
    ): RouteDecision {
        val packet = MeshPacket.decode(rawBytes) ?: return RouteDecision(null, false, null)

        // Skip our own origin frames looped back over peers
        if (packet.originTag.contentEquals(sessionOriginTag)) {
            return RouteDecision(packet, deliverLocally = false, relayPacket = null)
        }

        // Replay window check
        if (abs(nowMs - packet.timestampMs) > MeshPacket.REPLAY_WINDOW_MS) {
            return RouteDecision(packet, deliverLocally = false, relayPacket = null)
        }

        val hexId = packet.packetIdHex()
        if (seenIds.contains(hexId)) {
            return RouteDecision(packet, deliverLocally = false, relayPacket = null)
        }

        // Verify signature BEFORE recording in seenIds (prevents unauthenticated dedupe cache poisoning)
        if (!packet.verifySignature()) {
            return RouteDecision(null, deliverLocally = false, relayPacket = null)
        }

        markSeen(hexId)

        val myTag = MeshPacket.deriveTag(myDropId)
        val isMine = packet.isBroadcast() || (myDropId.isNotBlank() && packet.recipientTag.contentEquals(myTag))

        val relay = if ((!isMine || packet.isBroadcast()) && packet.ttlHops > 1 && shouldRelay(packet.ttlHops, peerCount)) {
            packet.decrementedHop().also { enqueueForForward(it, nowMs) }
        } else {
            null
        }
        return RouteDecision(packet, deliverLocally = isMine, relayPacket = relay)
    }

    @Synchronized
    fun pendingForwardPackets(nowMs: Long = System.currentTimeMillis()): List<MeshPacket> {
        pruneStore(nowMs)
        return storeQueue.map { it.second }
    }

    @Synchronized
    fun clear() {
        seenIds.clear()
        storeQueue.clear()
        signingKeyPair = MeshPacket.generateEphemeralSigningKeyPair()
    }

    private fun shouldRelay(ttlHops: Int, peerCount: Int): Boolean {
        if (ttlHops >= 4 || peerCount <= 10) return true
        val prob = when {
            peerCount <= 30 -> 0.85
            peerCount <= 50 -> 0.70
            else -> 0.55
        }
        return random.nextDouble() <= prob
    }

    private fun markSeen(hexId: String) {
        seenIds.add(hexId)
        while (seenIds.size > maxSeenPackets) {
            val first = seenIds.iterator().next()
            seenIds.remove(first)
        }
    }

    private fun enqueueForForward(packet: MeshPacket, nowMs: Long) {
        pruneStore(nowMs)
        val idHex = packet.packetIdHex()
        if (storeQueue.any { it.second.packetIdHex() == idHex }) return
        storeQueue.add(nowMs to packet)
        while (storeQueue.size > maxStoredForwardPackets) {
            storeQueue.removeAt(0)
        }
    }

    private fun pruneStore(nowMs: Long) {
        storeQueue.removeAll { nowMs - it.first > storeTtlMs }
    }
}
