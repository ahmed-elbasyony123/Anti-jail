package com.ghostkey.app.crypto

import org.junit.Assert.*
import org.junit.Test

class RendezvousPairingTest {

    // ─── generateCode ───────────────────────────────────────────────────────────

    @Test
    fun generateCode_producesFormattedCodeWithValidAlphabet() {
        repeat(20) {
            val code = RendezvousPairing.generateCode()
            // Must be XXXXX-XXXXX (11 chars with dash)
            assertTrue("formatted length should be 11, got ${code.length}", code.length == 11)
            assertTrue("must contain dash at position 5", code[5] == '-')
            val stripped = code.replace("-", "")
            assertEquals(RendezvousPairing.CODE_LEN, stripped.length)
            for (ch in stripped) {
                assertTrue("char '$ch' not in alphabet", ch in RendezvousPairing.ALPHABET)
            }
        }
    }

    @Test
    fun generateCode_producesUniqueCodes() {
        val codes = (1..50).map { RendezvousPairing.generateCode() }.toSet()
        assertTrue("expected unique codes, got collisions: ${50 - codes.size}", codes.size == 50)
    }

    // ─── normalizeCode ───────────────────────────────────────────────────────────

    @Test
    fun normalizeCode_acceptsValidCodeWithAndWithoutDash() {
        val formatted = RendezvousPairing.generateCode()       // e.g. "23456-78ABC"
        val canonical = formatted.replace("-", "")
        // Both should normalize to the same 10-char uppercase string
        assertEquals(canonical, RendezvousPairing.normalizeCode(formatted))
        assertEquals(canonical, RendezvousPairing.normalizeCode(canonical))
        // Lowercase version should also work
        assertEquals(canonical, RendezvousPairing.normalizeCode(canonical.lowercase()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeCode_rejectsTooShortCode() {
        RendezvousPairing.normalizeCode("ABCDE")
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeCode_rejectsTooLongCode() {
        RendezvousPairing.normalizeCode("ABCDEFGHIJK")  // 11 chars (no dash, so too many after strip)
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeCode_rejectsAmbiguousCharacters() {
        // '0', '1', 'I', 'O' are not in the alphabet
        RendezvousPairing.normalizeCode("0123456789")
    }

    // ─── formatCode ─────────────────────────────────────────────────────────────

    @Test
    fun formatCode_insertsHyphenAtPosition5() {
        val canonical = RendezvousPairing.generateCode().replace("-", "")
        val formatted = RendezvousPairing.formatCode(canonical)
        assertEquals(11, formatted.length)
        assertEquals('-', formatted[5])
        assertEquals(canonical.substring(0, 5), formatted.substring(0, 5))
        assertEquals(canonical.substring(5), formatted.substring(6))
    }

    // ─── extractCode ─────────────────────────────────────────────────────────────

    @Test
    fun extractCode_fromWebLink() {
        val code = RendezvousPairing.generateCode()          // "ABCDE-FGHJK"
        val canonical = code.replace("-", "")
        val link = "http://example.onion/#pair=$code"
        assertEquals(canonical, RendezvousPairing.extractCode(link))
    }

    @Test
    fun extractCode_fromAppDeepLink() {
        val code = RendezvousPairing.generateCode()
        val canonical = code.replace("-", "")
        val deepLink = "ghostkey://pair?c=$code"
        assertEquals(canonical, RendezvousPairing.extractCode(deepLink))
    }

    @Test
    fun extractCode_fromRawCode() {
        val code = RendezvousPairing.generateCode()
        val canonical = code.replace("-", "")
        // With dash
        assertEquals(canonical, RendezvousPairing.extractCode(code))
        // Without dash
        assertEquals(canonical, RendezvousPairing.extractCode(canonical))
    }

    @Test
    fun extractCode_fromWebLinkWithoutDashInFragment() {
        val code = RendezvousPairing.generateCode()
        val canonical = code.replace("-", "")
        val link = "http://relay.onion/#pair=$canonical"
        assertEquals(canonical, RendezvousPairing.extractCode(link))
    }

    @Test(expected = IllegalArgumentException::class)
    fun extractCode_emptyInput_throws() {
        RendezvousPairing.extractCode("")
    }

    // ─── buildWebInviteLink / buildAppInviteLink ──────────────────────────────

    @Test
    fun buildWebInviteLink_formatsCorrectly() {
        val code = RendezvousPairing.generateCode()
        val canonical = code.replace("-", "")
        val link = RendezvousPairing.buildWebInviteLink("http://relay.onion", canonical)
        assertTrue("link must start with relay base", link.startsWith("http://relay.onion/"))
        assertTrue("link must contain #pair=", link.contains("#pair="))
        // Extract should round-trip
        assertEquals(canonical, RendezvousPairing.extractCode(link))
    }

    @Test
    fun buildAppInviteLink_formatsCorrectly() {
        val code = RendezvousPairing.generateCode()
        val canonical = code.replace("-", "")
        val link = RendezvousPairing.buildAppInviteLink(canonical)
        assertTrue("app link must use ghostkey scheme", link.startsWith("ghostkey://pair?c="))
        assertEquals(canonical, RendezvousPairing.extractCode(link))
    }

    // ─── deriveSlotId ────────────────────────────────────────────────────────────

    @Test
    fun deriveSlotId_returns64CharLowercaseHex() {
        val canonical = RendezvousPairing.generateCode().replace("-", "")
        val slotId = RendezvousPairing.deriveSlotId(canonical)
        assertEquals(64, slotId.length)
        assertTrue("slotId must be lowercase hex", slotId.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun deriveSlotId_isDeterministic() {
        val canonical = RendezvousPairing.generateCode().replace("-", "")
        val id1 = RendezvousPairing.deriveSlotId(canonical)
        val id2 = RendezvousPairing.deriveSlotId(canonical)
        assertEquals(id1, id2)
    }

    @Test
    fun deriveSlotId_formattedVsCanonicalMatch() {
        val formatted = RendezvousPairing.generateCode()      // "ABCDE-FGHJK"
        val canonical = formatted.replace("-", "")
        // slotId must be same regardless of whether code has dash
        assertEquals(RendezvousPairing.deriveSlotId(formatted), RendezvousPairing.deriveSlotId(canonical))
    }

    @Test
    fun deriveSlotId_differentCodesProduceDifferentIds() {
        val id1 = RendezvousPairing.deriveSlotId(RendezvousPairing.generateCode().replace("-", ""))
        val id2 = RendezvousPairing.deriveSlotId(RendezvousPairing.generateCode().replace("-", ""))
        assertNotEquals(id1, id2)
    }

    // ─── sealContactCard / openContactCard ───────────────────────────────────────

    private fun fakeContactCardJson(alias: String = "Alice", dropId: String = "aabbccddeeff"): String {
        // We use a *fake* JSON that openContactCard will try to parse via InPersonPairing.parseContactCard.
        // Since InPersonPairing.parseContactCard calls StandalonePgpEngine which requires a real PGP key,
        // we test only sealContactCard (output format) here and separately test that wrong code decrypts to garbage.
        return """{"v":1,"type":"ghostkey_contact","alias":"$alias","dropId":"$dropId","fp":"AABBCCDD","key":"-----BEGIN PGP PUBLIC KEY BLOCK-----\nFAKE\n-----END PGP PUBLIC KEY BLOCK-----"}"""
    }

    @Test
    fun sealContactCard_producesValidJsonBlob() {
        val canonical = RendezvousPairing.generateCode().replace("-", "")
        val json = fakeContactCardJson()
        val sealed = RendezvousPairing.sealContactCard(canonical, json)

        // slotId should match deriveSlotId
        assertEquals(RendezvousPairing.deriveSlotId(canonical), sealed.slotId)

        // sealedBlob must be a parseable JSON with v, salt, iv, ct keys
        val blob = org.json.JSONObject(sealed.sealedBlob)
        assertEquals(1, blob.getInt("v"))
        assertTrue("salt must be non-empty", blob.getString("salt").isNotBlank())
        assertTrue("iv must be non-empty", blob.getString("iv").isNotBlank())
        assertTrue("ct must be non-empty", blob.getString("ct").isNotBlank())
    }

    @Test
    fun sealContactCard_producesUniqueIvsPerCall() {
        val canonical = RendezvousPairing.generateCode().replace("-", "")
        val json = fakeContactCardJson()
        val s1 = RendezvousPairing.sealContactCard(canonical, json)
        val s2 = RendezvousPairing.sealContactCard(canonical, json)
        val iv1 = org.json.JSONObject(s1.sealedBlob).getString("iv")
        val iv2 = org.json.JSONObject(s2.sealedBlob).getString("iv")
        assertNotEquals("IVs must differ (random each call)", iv1, iv2)
    }

    @Test
    fun wrongCode_failsDecryption() {
        val code1 = RendezvousPairing.generateCode().replace("-", "")
        val code2 = RendezvousPairing.generateCode().replace("-", "")
        // Ensure they're actually different (astronomically unlikely to collide)
        assumeTrue("codes must differ", code1 != code2)
        val json = fakeContactCardJson()
        val sealed = RendezvousPairing.sealContactCard(code1, json)

        // Decrypting with a different code must throw (AES-GCM authentication failure)
        try {
            RendezvousPairing.openContactCard(code2, sealed.sealedBlob)
            fail("Expected decryption to fail with wrong code")
        } catch (e: Exception) {
            // Expected: javax.crypto.AEADBadTagException or IllegalArgumentException wrapping it
            assertTrue(
                "Exception should be crypto or arg error",
                e is IllegalArgumentException || e is javax.crypto.AEADBadTagException || e.cause is javax.crypto.AEADBadTagException
            )
        }
    }

    @Test
    fun sealContactCard_malformedBlob_throws() {
        val canonical = RendezvousPairing.generateCode().replace("-", "")
        try {
            RendezvousPairing.openContactCard(canonical, "not-a-json")
            fail("Expected IllegalArgumentException for malformed blob")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Malformed") == true)
        }
    }

    @Test
    fun sealContactCard_wrongVersion_throws() {
        val canonical = RendezvousPairing.generateCode().replace("-", "")
        val badBlob = """{"v":99,"salt":"aaa","iv":"bbb","ct":"ccc"}"""
        try {
            RendezvousPairing.openContactCard(canonical, badBlob)
            fail("Expected IllegalArgumentException for unsupported version")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    // Helper to skip tests when assumption fails (JUnit 4 doesn't have Assume easily,
    // so use a simple check instead)
    private fun assumeTrue(msg: String, condition: Boolean) {
        if (!condition) {
            println("SKIPPED: $msg")
            throw org.junit.AssumptionViolatedException(msg)
        }
    }
}
