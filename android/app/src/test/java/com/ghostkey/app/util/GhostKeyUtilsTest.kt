package com.ghostkey.app.util

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class GhostKeyUtilsTest {

    @Test
    fun bytesToHex_and_sha256Hex_produceExpectedDigests() {
        val raw = byteArrayOf(0x00, 0x0F, 0xA5.toByte(), 0xFF.toByte())
        assertEquals("000fa5ff", GhostKeyUtils.bytesToHex(raw))
        assertEquals("000FA5FF", GhostKeyUtils.bytesToHex(raw, uppercase = true))
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            GhostKeyUtils.sha256Hex("hello")
        )
    }

    @Test
    fun readStreamLimited_enforcesByteCap() {
        val small = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), GhostKeyUtils.readStreamLimited(small, 4))

        val tooLarge = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(GhostKeyUtils.readStreamLimited(tooLarge, 4))
    }

    @Test
    fun sanitizers_stripControlCharsAndDefaultScheme() {
        assertEquals("Alice", GhostKeyUtils.sanitizeAlias("  Alice\n<>\t\u202E  "))
        assertEquals("http://abc123.onion", GhostKeyUtils.sanitizeRelayUrl("abc123.onion/"))
        assertEquals("https://relay.example.com", GhostKeyUtils.sanitizeRelayUrl("relay.example.com/"))
        assertEquals(4, GhostKeyUtils.calculateInSampleSize(4000, 3000, 1024))
    }
}
