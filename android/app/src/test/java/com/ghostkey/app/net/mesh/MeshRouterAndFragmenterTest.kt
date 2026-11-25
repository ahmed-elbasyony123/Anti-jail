package com.ghostkey.app.net.mesh

import org.junit.Assert.*
import org.junit.Test

class MeshRouterAndFragmenterTest {

    @Test
    fun signedPacket_roundTripsAndRejectsTampering() {
        val kp = MeshPacket.generateEphemeralSigningKeyPair()
        val packet = MeshPacket(
            ttlHops = 5,
            packetId = ByteArray(8) { 1 },
            originTag = ByteArray(8) { 2 },
            recipientTag = MeshPacket.deriveTag("abc"),
            timestampMs = 1_700_000_000_000L,
            payload = "ciphertext".toByteArray()
        ).signedWith(kp.private, kp.public)

        val decoded = MeshPacket.decode(packet.encode())
        assertNotNull(decoded)
        assertTrue(decoded!!.verifySignature())

        // Signature must survive TTL decrement on hop
        val hopped = MeshPacket.decode(decoded.decrementedHop().encode())!!
        assertEquals(4, hopped.ttlHops)
        assertTrue(hopped.verifySignature())

        // Payload tampering invalidates signature
        val tampered = hopped.copy(payload = "tampered!".toByteArray())
        assertFalse(tampered.verifySignature())
    }

    @Test
    fun router_resistsDedupeCachePoisoningAndEnforcesReplayWindow() {
        val senderRouter = MeshRouter()
        val receiverRouter = MeshRouter()
        val now = 1_700_000_000_000L
        val targetDrop = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val valid = senderRouter.createOutgoingPacket(
            recipientDropId = targetDrop,
            payloadBytes = "encrypted-envelope".toByteArray(),
            ttlHops = 4,
            nowMs = now
        )

        // Attacker injects forged packet carrying same packetId but bad signature
        val poisoned = valid.copy(signature = ByteArray(64) { 0x55 }).encode()
        val poisonDecision = receiverRouter.onIncomingBytes(poisoned, myDropId = targetDrop, nowMs = now)
        assertFalse(poisonDecision.deliverLocally)
        assertNull(poisonDecision.relayPacket)

        // Authentic packet with the same packetId MUST still be accepted
        val authenticDecision = receiverRouter.onIncomingBytes(valid.encode(), myDropId = targetDrop, nowMs = now)
        assertTrue(authenticDecision.deliverLocally)

        // Replay of the same authentic packet is dropped
        val replayDecision = receiverRouter.onIncomingBytes(valid.encode(), myDropId = targetDrop, nowMs = now)
        assertFalse(replayDecision.deliverLocally)

        // Packet outside 5-minute replay window is rejected
        val stale = senderRouter.createOutgoingPacket(targetDrop, "stale".toByteArray(), nowMs = now - 600_000L)
        val staleDecision = receiverRouter.onIncomingBytes(stale.encode(), myDropId = targetDrop, nowMs = now)
        assertFalse(staleDecision.deliverLocally)
    }

    @Test
    fun fragmenter_reassemblesOutOfOrderAndRejectsOverwriteAttack() {
        val fragmenter = BleFragmenter(chunkDataSize = 10, maxGlobalBufferedBytes = 1024L)
        val payload = ByteArray(35) { it.toByte() }
        val chunks = fragmenter.fragment(payload)
        assertEquals(4, chunks.size)

        // Out-of-order first and last chunks
        assertNull(fragmenter.onChunkReceived(chunks[2]))
        assertNull(fragmenter.onChunkReceived(chunks[0]))

        // Attacker tries to overwrite already-held chunk 0 with corrupted byte
        val forgedChunk0 = chunks[0].copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(fragmenter.onChunkReceived(forgedChunk0))

        assertNull(fragmenter.onChunkReceived(chunks[3]))
        val reassembled = fragmenter.onChunkReceived(chunks[1])
        assertNotNull(reassembled)
        assertArrayEquals(payload, reassembled)
    }
}
