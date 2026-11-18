package com.ghostkey.app.crypto

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.operator.jcajce.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.util.Date

data class PgpKeyPairArmored(
    val publicKeyArmored: String,
    val privateKeyArmored: String,
    val fingerprint: String
)

/**
 * Standalone, Self-Contained OpenPGP Cryptographic Engine for GhostKey.
 * Runs in volatile memory (RAM only) using Bouncy Castle 1.78.
 * Requires ZERO external apps, permissions, or disk persistence.
 */
object StandalonePgpEngine {

    val bcProvider: Provider = BouncyCastleProvider()

    init {
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        } catch (_: Exception) {}
        Security.insertProviderAt(bcProvider, 1)
    }

    /**
     * Generate an ephemeral OpenPGP RSA-2048 keypair in memory.
     */
    fun generateKeyPair(userId: String = "ghostkey@stealth.local"): PgpKeyPairArmored {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val masterJavaPair = kpg.generateKeyPair()

        val subKpg = KeyPairGenerator.getInstance("RSA")
        subKpg.initialize(2048, SecureRandom())
        val subJavaPair = subKpg.generateKeyPair()

        val sha1Calc = JcaPGPDigestCalculatorProviderBuilder().setProvider(bcProvider).build().get(HashAlgorithmTags.SHA1)

        val masterPgpPair = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, masterJavaPair, Date())
        val subPgpPair = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, subJavaPair, Date())

        val masterSubpacketGen = PGPSignatureSubpacketGenerator()
        masterSubpacketGen.setKeyFlags(false, KeyFlags.SIGN_DATA or KeyFlags.CERTIFY_OTHER)
        masterSubpacketGen.setPreferredSymmetricAlgorithms(
            false,
            intArrayOf(
                SymmetricKeyAlgorithmTags.AES_256,
                SymmetricKeyAlgorithmTags.AES_192,
                SymmetricKeyAlgorithmTags.AES_128
            )
        )
        masterSubpacketGen.setPreferredHashAlgorithms(
            false,
            intArrayOf(
                HashAlgorithmTags.SHA256,
                HashAlgorithmTags.SHA384,
                HashAlgorithmTags.SHA512
            )
        )

        val encryptor = JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, sha1Calc)
            .setProvider(bcProvider)
            .build("".toCharArray())

        val keyRingGen = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            masterPgpPair,
            userId,
            sha1Calc,
            masterSubpacketGen.generate(),
            null,
            JcaPGPContentSignerBuilder(masterPgpPair.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider(bcProvider),
            encryptor
        )

        val subpacketGen = PGPSignatureSubpacketGenerator()
        subpacketGen.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
        keyRingGen.addSubKey(subPgpPair, subpacketGen.generate(), null)

        val secRing = keyRingGen.generateSecretKeyRing()
        val pubRing = keyRingGen.generatePublicKeyRing()

        // Armor public key
        val pubOut = ByteArrayOutputStream()
        ArmoredOutputStream(pubOut).use { it.write(pubRing.encoded) }
        val pubArmored = pubOut.toString(Charsets.UTF_8.name())

        // Armor private key
        val secOut = ByteArrayOutputStream()
        ArmoredOutputStream(secOut).use { it.write(secRing.encoded) }
        val secArmored = secOut.toString(Charsets.UTF_8.name())

        val fp = pubRing.publicKey.getFingerprint().joinToString("") { "%02X".format(it) }

        return PgpKeyPairArmored(pubArmored, secArmored, fp)
    }

    /**
     * Read and find the appropriate encryption public key from an armored block.
     */
    fun readPublicKey(armored: String): PGPPublicKey {
        val stream = PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8)))
        val pubRingCol = PGPPublicKeyRingCollection(stream, JcaKeyFingerprintCalculator().setProvider(bcProvider))

        var fallbackKey: PGPPublicKey? = null
        for (ring in pubRingCol) {
            for (key in ring) {
                if (fallbackKey == null) fallbackKey = key
                if (key.isEncryptionKey) {
                    return key
                }
            }
        }
        return fallbackKey ?: throw IllegalArgumentException("No valid OpenPGP public key found in text")
    }

    /**
     * Extract the master fingerprint as a 40-character hex string.
     */
    fun getFingerprint(armored: String): String {
        val stream = PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8)))
        val pubRingCol = PGPPublicKeyRingCollection(stream, JcaKeyFingerprintCalculator().setProvider(bcProvider))
        for (ring in pubRingCol) {
            val masterKey = ring.publicKey
            if (masterKey != null) {
                return masterKey.getFingerprint().joinToString("") { "%02X".format(it) }
            }
        }
        throw IllegalArgumentException("Could not extract fingerprint from key block")
    }

    /**
     * Encrypt raw data into an armored OpenPGP ASCII block.
     */
    fun encrypt(data: ByteArray, recipientArmoredPubKey: String): String {
        val recipientKey = readPublicKey(recipientArmoredPubKey)

        val encDataGen = PGPEncryptedDataGenerator(
            JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(SecureRandom())
                .setProvider(bcProvider)
        )
        encDataGen.addMethod(
            JcePublicKeyKeyEncryptionMethodGenerator(recipientKey).setProvider(bcProvider)
        )

        val out = ByteArrayOutputStream()
        val armoredOut = ArmoredOutputStream(out)
        val encOut = encDataGen.open(armoredOut, ByteArray(4096))

        val litDataGen = PGPLiteralDataGenerator()
        val litOut = litDataGen.open(
            encOut,
            PGPLiteralData.BINARY,
            PGPLiteralData.CONSOLE,
            data.size.toLong(),
            Date()
        )
        litOut.write(data)
        litOut.close()

        encOut.close()
        armoredOut.close()

        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Decrypt an armored OpenPGP message using our armored private key.
     */
    fun decrypt(armoredMessage: String, privateKeyArmored: String): ByteArray {
        val privStream = PGPUtil.getDecoderStream(ByteArrayInputStream(privateKeyArmored.toByteArray(Charsets.UTF_8)))
        val privRingCollection = PGPSecretKeyRingCollection(privStream, JcaKeyFingerprintCalculator().setProvider(bcProvider))

        val msgStream = PGPUtil.getDecoderStream(ByteArrayInputStream(armoredMessage.toByteArray(Charsets.UTF_8)))
        val pgpFact = PGPObjectFactory(msgStream, JcaKeyFingerprintCalculator().setProvider(bcProvider))

        var obj = pgpFact.nextObject()
        var encList: PGPEncryptedDataList? = null
        while (obj != null) {
            if (obj is PGPEncryptedDataList) {
                encList = obj
                break
            }
            obj = pgpFact.nextObject()
        }

        if (encList == null) {
            throw IllegalArgumentException("No encrypted data found in message")
        }

        var privKey: PGPPrivateKey? = null
        var pbe: PGPPublicKeyEncryptedData? = null

        val decryptor = JcePBESecretKeyDecryptorBuilder().setProvider(bcProvider).build("".toCharArray())

        for (item in encList) {
            if (item is PGPPublicKeyEncryptedData) {
                val secKey = privRingCollection.getSecretKey(item.keyID)
                if (secKey != null) {
                    try {
                        privKey = secKey.extractPrivateKey(decryptor)
                        pbe = item
                        break
                    } catch (e: Exception) {
                        try {
                            privKey = secKey.extractPrivateKey(null)
                            pbe = item
                            break
                        } catch (e2: Exception) {
                            // continue trying
                        }
                    }
                }
            }
        }

        // Fallback: try all available secret keys in the ring
        if (pbe == null || privKey == null) {
            for (secRing in privRingCollection) {
                for (secKey in secRing) {
                    for (item in encList) {
                        if (item is PGPPublicKeyEncryptedData) {
                            try {
                                privKey = secKey.extractPrivateKey(decryptor)
                                pbe = item
                                break
                            } catch (e: Exception) {
                                try {
                                    privKey = secKey.extractPrivateKey(null)
                                    pbe = item
                                    break
                                } catch (e2: Exception) {
                                    // continue
                                }
                            }
                        }
                    }
                    if (pbe != null && privKey != null) break
                }
                if (pbe != null && privKey != null) break
            }
        }

        if (pbe == null || privKey == null) {
            throw IllegalArgumentException("Could not find matching private key to decrypt payload")
        }

        val clearStream: InputStream = pbe.getDataStream(
            JcePublicKeyDataDecryptorFactoryBuilder().setProvider(bcProvider).build(privKey)
        )
        val plainFact = PGPObjectFactory(clearStream, JcaKeyFingerprintCalculator().setProvider(bcProvider))
        var plainObj = plainFact.nextObject()

        if (plainObj is PGPCompressedData) {
            val compFact = PGPObjectFactory(plainObj.dataStream, JcaKeyFingerprintCalculator().setProvider(bcProvider))
            plainObj = compFact.nextObject()
        }

        if (plainObj is PGPLiteralData) {
            return plainObj.inputStream.readBytes()
        }

        throw IllegalArgumentException("Expected literal data in decrypted stream, got ${plainObj?.javaClass?.simpleName}")
    }

    /**
     * Deterministic Blind Mailbox Hash: SHA-256(fingerprint + salt).
     */
    fun deriveMailbox(fingerprint: String): String {
        val cleanFp = fingerprint.replace(" ", "").trim().uppercase()
        val raw = "$cleanFp:GHOSTKEY_RAM_SALT_v1"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Unified 30-digit Safety Code for MITM verification.
     */
    fun generateSafetyCode(fp1: String, fp2: String): String {
        val clean1 = fp1.replace(" ", "").trim().uppercase()
        val clean2 = fp2.replace(" ", "").trim().uppercase()
        val sorted = listOf(clean1, clean2).sorted().joinToString(":")
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(sorted.toByteArray(Charsets.UTF_8))

        val fullDigits = StringBuilder()
        for (i in 0 until 15) {
            val b1 = hash[i * 2].toInt() and 0xFF
            val b2 = hash[i * 2 + 1].toInt() and 0xFF
            val num = ((b1 shl 8) or b2) % 100000
            fullDigits.append(String.format("%05d", num))
        }
        return fullDigits.toString().chunked(5).take(6).joinToString(" ")
    }
}
