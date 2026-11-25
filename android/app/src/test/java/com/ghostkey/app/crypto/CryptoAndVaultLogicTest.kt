package com.ghostkey.app.crypto

import org.junit.Assert.*
import org.junit.Test

class CryptoAndVaultLogicTest {

    @Test
    fun pgpEngine_signEncryptDecryptRoundTrip() {
        val alice = StandalonePgpEngine.generateKeyPair("Alice")
        val bob = StandalonePgpEngine.generateKeyPair("Bob")

        assertTrue(StandalonePgpEngine.isValidHex32(alice.dropId))
        assertTrue(StandalonePgpEngine.isValidHex32(alice.readToken))
        assertEquals(alice.dropId, StandalonePgpEngine.deriveDropId(alice.readToken))

        val sasAB = StandalonePgpEngine.generateSafetyCode(alice.fingerprint, bob.fingerprint)
        val sasBA = StandalonePgpEngine.generateSafetyCode(bob.fingerprint, alice.fingerprint)
        assertEquals(sasAB, sasBA)

        val message = "Secret GhostKey payload".toByteArray()
        val armor = StandalonePgpEngine.encrypt(message, bob.publicKeyArmored, alice.privateKeyArmored)
        val decrypted = StandalonePgpEngine.decryptMulti(armor, bob.privateKeyArmored, listOf(alice.publicKeyArmored))

        assertArrayEquals(message, decrypted.bytes)
        assertEquals(SignatureStatus.VERIFIED, decrypted.signatureStatus)
        assertEquals(alice.fingerprint, decrypted.signerFingerprint)
    }

    @Test
    fun groupCrypto_encryptDecryptAndRejectsWrongKey() {
        val key1 = GroupCrypto.generateGroupKey()
        val key2 = GroupCrypto.generateGroupKey()
        val aad = "group-42".toByteArray()
        val plain = "Group message".toByteArray()

        val ct = GroupCrypto.encrypt(plain, key1, aad)
        assertArrayEquals(plain, GroupCrypto.decrypt(ct, key1, aad))

        try {
            GroupCrypto.decrypt(ct, key2, aad)
            fail("Expected decryption with wrong key to fail")
        } catch (_: Exception) {
            // expected AEAD tag verification failure
        }
    }
}
