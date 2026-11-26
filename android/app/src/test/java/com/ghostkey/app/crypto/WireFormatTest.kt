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
}
