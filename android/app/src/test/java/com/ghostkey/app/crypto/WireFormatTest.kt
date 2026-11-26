package com.ghostkey.app.crypto

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WireFormatTest {

    @Test
    fun textEnvelope_roundTripsAndAlignsPadding() {
        val raw = WireFormat.buildText(
            text = "hello ghostkey",
            senderAlias = "Alice",
            ttlSeconds = 300L,
            replyTo = "parent-123",
            msgId = "msg-abc"
        )
        assertEquals(0, raw.size % 512)
        val parsed = WireFormat.parse(raw)
        assertNotNull(parsed)
        assertEquals(WireMessage.TYPE_TEXT, parsed!!.type)
        assertEquals("hello ghostkey", parsed.text)
        assertEquals("Alice", parsed.senderAlias)
        assertEquals(300L, parsed.ttlSeconds)
        assertEquals("parent-123", parsed.replyTo)
        assertEquals("msg-abc", parsed.msgId)
    }

    @Test
    fun groupInvite_acceptsGeneratedBase64GroupKeyAndRejectsMismatchedDrop() {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val dropId = StandalonePgpEngine.deriveDropId(token)
        val groupKey = GroupCrypto.generateGroupKey()

        val valid = WireFormat.buildGroupInvite(
            groupId = "grp-1",
            groupName = "Alpha",
            groupDropId = dropId,
            groupReadToken = token,
            sharedKey = groupKey,
            senderAlias = "Alice"
        )
        val parsed = WireFormat.parse(valid)
        assertNotNull(parsed)
        assertTrue(parsed!!.isGroupInvite)
        assertEquals(groupKey, parsed.sharedKey)

        val forgedDrop = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        val forged = WireFormat.buildGroupInvite(
            groupId = "grp-1",
            groupName = "Alpha",
            groupDropId = forgedDrop,
            groupReadToken = token,
            sharedKey = groupKey
        )
        assertNull(WireFormat.parse(forged))
    }

    @Test
    fun profileUpdate_parsesAndEnforcesAvatarSizeCap() {
        val smallAvatar = Base64.getEncoder().encodeToString(ByteArray(1024) { 0x42 })
        val valid = WireFormat.buildProfileUpdate(
            profileAlias = "Bob<b>",
            avatarBase64 = smallAvatar,
            avatarHash = "abcd1234",
            senderAlias = "Bob"
        )
        val parsed = WireFormat.parse(valid)
        assertNotNull(parsed)
        assertTrue(parsed!!.isProfileUpdate)
        assertEquals(smallAvatar, parsed.avatarBase64)
        assertEquals("abcd1234", parsed.avatarHash)

        val oversizedAvatar = Base64.getEncoder().encodeToString(ByteArray(70 * 1024))
        val bad = WireFormat.buildProfileUpdate(avatarBase64 = oversizedAvatar)
        assertNull(WireFormat.parse(bad))
    }

    @Test
    fun groupControl_validatesRotateKeyAndLeave() {
        val newKey = GroupCrypto.generateGroupKey()
        val rotate = WireFormat.buildGroupControl(
            groupId = "grp-9",
            action = WireMessage.GROUP_ACTION_KEY_ROTATE,
            newSharedKey = newKey
        )
        val parsedRotate = WireFormat.parse(rotate)
        assertNotNull(parsedRotate)
        assertEquals(newKey, parsedRotate!!.groupControlNewKey)

        val badRotate = WireFormat.buildGroupControl(
            groupId = "grp-9",
            action = WireMessage.GROUP_ACTION_KEY_ROTATE,
            newSharedKey = "not-a-valid-32-byte-key"
        )
        assertNull(WireFormat.parse(badRotate))
    }

    @Test
    fun mediaEnvelope_sanitizesPathTraversalFilename() {
        val bytes = WireFormat.buildMedia(
            mimeType = "image/webp",
            filename = "../../secret/photo.webp",
            bytes = byteArrayOf(1, 2, 3, 4)
        )
        val parsed = WireFormat.parse(bytes)
        assertNotNull(parsed)
        assertFalse(parsed!!.filename!!.contains(".."))
        assertFalse(parsed.filename!!.contains("/"))
    }

    @Test
    fun isStructuredEnvelope_detectsUnsupportedVersionsAndLeavesPlainText() {
        assertTrue(WireFormat.isStructuredEnvelope("""{"v":99,"type":"ephemeral_v2","text":"secret"}""".toByteArray()))
        assertTrue(WireFormat.isStructuredEnvelope("""{"type":"future_feature"}""".toByteArray()))
        assertFalse(WireFormat.isStructuredEnvelope("plain legacy text message".toByteArray()))
        assertFalse(WireFormat.isStructuredEnvelope("""{"hello":"world"}""".toByteArray()))
    }

    @Test
    fun clearChatEnvelope_roundTripsCleanly() {
        val raw = WireFormat.buildClearChat(senderAlias = "Alice")
        val parsed = WireFormat.parse(raw)
        assertNotNull(parsed)
        assertTrue(parsed!!.isClearChat)
        assertEquals("Alice", parsed.senderAlias)
    }

    // ─── contact_request ──────────────────────────────────────────────────────

    @Test
    fun contactRequest_roundTripsAllFields() {
        val alias = "Bob"
        // dropId must be 64 lowercase hex chars (matches StandalonePgpEngine.isValidHex32 / HEX_64 regex)
        val dropId = "aabbccddeeff00112233445566778899aabbccddeeff001122334455667788aa"
        val fp = "AABB CCDD EEFF 0011"
        val key = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nFAKEKEYDATA\n-----END PGP PUBLIC KEY BLOCK-----"
        val senderAlias = "Bob (sender)"
        val msgId = "req-msg-001"

        val raw = WireFormat.buildContactRequest(
            alias = alias,
            dropId = dropId,
            fingerprint = fp,
            publicKeyArmored = key,
            senderAlias = senderAlias,
            msgId = msgId
        )

        // Size must be 512-byte aligned
        assertEquals(0, raw.size % 512)

        val parsed = WireFormat.parse(raw)
        assertNotNull(parsed)
        assertTrue(parsed!!.isContactRequest)
        assertEquals(WireMessage.TYPE_CONTACT_REQUEST, parsed.type)

        // alias and fingerprint stored cleaned/normalized
        assertEquals(alias, parsed.requestAlias)
        assertEquals(dropId.trim().lowercase(), parsed.requestDropId)
        assertEquals(fp.trim().uppercase(), parsed.requestFingerprint)
        assertEquals(key.trim(), parsed.requestPublicKeyArmored)

        // senderAlias and msgId
        assertEquals(msgId, parsed.msgId)
    }

    @Test
    fun contactRequest_missingDropId_returnsNull() {
        // empty dropId fails isValidHex32 (needs 64 lowercase hex chars)
        val raw = WireFormat.buildContactRequest(
            alias = "Eve",
            dropId = "",           // empty → should fail parse
            fingerprint = "AABBCCDD",
            publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nX\n-----END PGP PUBLIC KEY BLOCK-----"
        )
        // dropId.trim().lowercase() will be "" → parser should reject
        val parsed = WireFormat.parse(raw)
        assertNull("empty dropId must be rejected", parsed)
    }

    @Test
    fun contactRequest_missingKey_returnsNull() {
        val raw = WireFormat.buildContactRequest(
            alias = "Eve",
            dropId = "aabbccddeeff00112233445566778899aabbccddeeff001122334455667788aa",
            fingerprint = "AABBCCDD",
            publicKeyArmored = ""   // empty key → reject
        )
        val parsed = WireFormat.parse(raw)
        assertNull("empty publicKeyArmored must be rejected", parsed)
    }

    @Test
    fun contactRequest_withAvatar_roundTrips() {
        val avatarB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(512) { 0xAB.toByte() })
        val avatarHash = "deadbeef"
        val raw = WireFormat.buildContactRequest(
            alias = "Carol",
            dropId = "1122334455660000aabbccddeeff001122334455667788990011223344556677",
            fingerprint = "11223344",
            publicKeyArmored = "-----BEGIN PGP PUBLIC KEY BLOCK-----\nAVATAR\n-----END PGP PUBLIC KEY BLOCK-----",
            avatarBase64 = avatarB64,
            avatarHash = avatarHash
        )
        val parsed = WireFormat.parse(raw)
        assertNotNull(parsed)
        assertTrue(parsed!!.isContactRequest)
        assertEquals(avatarHash, parsed.avatarHash)
        assertEquals(avatarB64, parsed.avatarBase64)
    }
}
