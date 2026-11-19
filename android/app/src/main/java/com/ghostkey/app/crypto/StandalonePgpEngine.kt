package com.ghostkey.app.crypto

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec
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

/**
 * Everything the app needs after creating a new identity.
 * [publicKeyArmored] already carries the "Comment: Drop-ID: ..." header so that a peer who
 * pastes it learns both the PGP key and where to send messages.
 * [readToken] is secret and must never be shared with the peer or put in the public key block.
 */
data class PgpKeyPairArmored(
    val publicKeyArmored: String,
    val privateKeyArmored: String,
    val fingerprint: String,
    val dropId: String,
    val readToken: String
)

enum class SignatureStatus { VERIFIED, UNSIGNED, INVALID_SIGNATURE }

class DecryptedMessage(
    val bytes: ByteArray,
    val signatureStatus: SignatureStatus,
    /** Master-key fingerprint of the signer. Only set when [signatureStatus] is VERIFIED. */
    val signerFingerprint: String?
)

/** What we learn from a pasted peer public key block. */
data class PeerKeyInfo(
    val publicKeyArmored: String,
    val fingerprint: String,
    val dropId: String?
)

/**
 * Standalone, self-contained OpenPGP engine for GhostKey (Bouncy Castle 1.78).
 *
 * Keys: Ed25519 (sign/certify primary key) + X25519 (encryption subkey), OpenPGP v4
 * "legacy" packet formats for broad interoperability. All signatures use SHA-256.
 * (SHA-1 only remains where the v4 format itself requires it: key fingerprints and the
 * secret-key checksum; it is never used to sign anything.)
 *
 * Messages are signed first and then encrypted (the signature is inside the ciphertext), so
 * the relay cannot see who signed a message.
 */
object StandalonePgpEngine {

    val bcProvider: Provider = BouncyCastleProvider()

    private val DROP_ID_COMMENT = Regex("""(?im)^Comment:\s*Drop-ID:\s*([0-9a-f]{64})\s*$""")
    private val DROP_ID_USER_ID = Regex("""drop:([0-9a-f]{64})""")
    private val HEX_64 = Regex("^[0-9a-f]{64}$")

    private val secureRandom = SecureRandom()

    // Thread-safe key caches for instant crypto operations (optimization 3.1)
    private val publicKeyCache = java.util.concurrent.ConcurrentHashMap<String, PGPPublicKey>()
    private val signingKeyCache = java.util.concurrent.ConcurrentHashMap<String, Pair<PGPSecretKey, PGPPrivateKey>>()
    private val secretRingCollectionCache = java.util.concurrent.ConcurrentHashMap<String, PGPSecretKeyRingCollection>()
    private val peerKeyInfoCache = java.util.concurrent.ConcurrentHashMap<String, PeerKeyInfo>()

    fun clearCache() {
        publicKeyCache.clear()
        signingKeyCache.clear()
        secretRingCollectionCache.clear()
        peerKeyInfoCache.clear()
    }

    init {
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        } catch (_: Exception) {}
        Security.insertProviderAt(bcProvider, 1)
    }

    private fun fingerprintCalc() = JcaKeyFingerprintCalculator().setProvider(bcProvider)

    private fun hex(bytes: ByteArray, upper: Boolean = false): String =
        bytes.joinToString("") { if (upper) "%02X".format(it) else "%02x".format(it) }

    fun randomHex32(): String {
        val b = ByteArray(32)
        secureRandom.nextBytes(b)
        return hex(b)
    }

    fun isValidHex32(value: String?): Boolean = value != null && HEX_64.matches(value)

    /**
     * Cryptographically derives a dropId from a secret readToken: dropId = SHA-256("drop:" + readToken).
     * Eliminates dropId squatting and front-running entirely (loophole P1).
     */
    fun deriveDropId(readToken: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(("drop:" + readToken.lowercase()).toByteArray(Charsets.UTF_8))
        return hex(digest)
    }

    // ---------------------------------------------------------------------------------------
    // Key generation
    // ---------------------------------------------------------------------------------------

    /**
     * Generate a fresh identity: Ed25519 primary key + X25519 encryption subkey, plus a random
     * 32-byte hex [PgpKeyPairArmored.readToken] and cryptographically derived [PgpKeyPairArmored.dropId].
     *
     * The dropId is also bound into the key's self-certified User ID, so a peer can detect a
     * swapped "Drop-ID" comment header (the armor header itself is not signed).
     */
    fun generateKeyPair(): PgpKeyPairArmored {
        val readToken = randomHex32()
        val dropId = deriveDropId(readToken)
        val userId = "ghostkey drop:$dropId"

        val edGen = KeyPairGenerator.getInstance("EdDSA", bcProvider)
        edGen.initialize(ECNamedCurveGenParameterSpec("Ed25519"), secureRandom)
        val masterPgpPair = JcaPGPKeyPair(PGPPublicKey.EDDSA_LEGACY, edGen.generateKeyPair(), Date())

        val xGen = KeyPairGenerator.getInstance("XDH", bcProvider)
        xGen.initialize(ECNamedCurveGenParameterSpec("X25519"), secureRandom)
        val subPgpPair = JcaPGPKeyPair(PGPPublicKey.ECDH, xGen.generateKeyPair(), Date())

        val sha1Calc = JcaPGPDigestCalculatorProviderBuilder().setProvider(bcProvider).build()
            .get(HashAlgorithmTags.SHA1)

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

        // Empty passphrase: at-rest protection of the private key is handled by the app
        // (Android Keystore encryption), not by an OpenPGP passphrase.
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
            JcaPGPContentSignerBuilder(masterPgpPair.publicKey.algorithm, HashAlgorithmTags.SHA256)
                .setProvider(bcProvider),
            encryptor
        )

        val subpacketGen = PGPSignatureSubpacketGenerator()
        subpacketGen.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
        keyRingGen.addSubKey(subPgpPair, subpacketGen.generate(), null)

        val secRing = keyRingGen.generateSecretKeyRing()
        val pubRing = keyRingGen.generatePublicKeyRing()

        val pubOut = ByteArrayOutputStream()
        ArmoredOutputStream(pubOut).use {
            it.setHeader("Comment", "Drop-ID: $dropId")
            it.write(pubRing.encoded)
        }
        val pubArmored = pubOut.toString(Charsets.UTF_8.name())

        val secOut = ByteArrayOutputStream()
        ArmoredOutputStream(secOut).use { it.write(secRing.encoded) }
        val secArmored = secOut.toString(Charsets.UTF_8.name())

        val fp = hex(pubRing.publicKey.fingerprint, upper = true)

        return PgpKeyPairArmored(pubArmored, secArmored, fp, dropId, readToken)
    }

    // ---------------------------------------------------------------------------------------
    // Public key parsing
    // ---------------------------------------------------------------------------------------

    private fun readPublicRings(armored: String): PGPPublicKeyRingCollection {
        val stream = PGPUtil.getDecoderStream(ByteArrayInputStream(armored.toByteArray(Charsets.UTF_8)))
        return PGPPublicKeyRingCollection(stream, fingerprintCalc())
    }

    private fun isExpired(key: PGPPublicKey, now: Date): Boolean {
        val validSeconds = key.validSeconds
        if (validSeconds <= 0L) return false
        return key.creationTime.time + validSeconds * 1000L < now.time
    }

    private fun isUsable(key: PGPPublicKey, now: Date): Boolean = !key.isRevoked && !isExpired(key, now)

    /**
     * Find the encryption subkey of an armored public key block. Revoked or expired subkeys
     * are skipped; if the primary key itself is revoked or expired the whole block is rejected.
     */
    fun readPublicKey(armored: String, now: Date = Date()): PGPPublicKey {
        val trimmed = armored.trim()
        val cached = publicKeyCache[trimmed]
        if (cached != null && isUsable(cached, now)) {
            return cached
        }
        for (ring in readPublicRings(trimmed)) {
            val master = ring.publicKey ?: continue
            if (!isUsable(master, now)) continue
            for (key in ring) {
                if (key.isEncryptionKey && isUsable(key, now)) {
                    publicKeyCache[trimmed] = key
                    return key
                }
            }
        }
        throw IllegalArgumentException("No valid (non-revoked, non-expired) encryption key found in text")
    }

    /**
     * Extract the master fingerprint as a 40-character hex string.
     */
    fun getFingerprint(armored: String): String {
        for (ring in readPublicRings(armored)) {
            val masterKey = ring.publicKey
            if (masterKey != null) {
                return hex(masterKey.fingerprint, upper = true)
            }
        }
        throw IllegalArgumentException("Could not extract fingerprint from key block")
    }

    /**
     * Read the peer's Drop-ID from a pasted public key block.
     *
     * Preferred source is the key's own certified User ID ("ghostkey drop:<id>"), which cannot be
     * altered without breaking the self-signature. The "Comment: Drop-ID:" armor header is the
     * convenience copy; if both exist they must agree. Returns null if the block has no Drop-ID.
     */
    fun extractDropId(armored: String): String? {
        val fromComment = DROP_ID_COMMENT.find(armored)?.groupValues?.get(1)?.lowercase()

        var fromUserId: String? = null
        for (ring in readPublicRings(armored)) {
            val master = ring.publicKey ?: continue
            val uids = master.userIDs
            while (uids.hasNext()) {
                val uid = uids.next() as String
                val match = DROP_ID_USER_ID.find(uid) ?: continue
                if (!selfCertificationValid(master, uid)) {
                    throw IllegalArgumentException("Public key User ID has an invalid self-signature")
                }
                fromUserId = match.groupValues[1].lowercase()
            }
        }

        if (fromUserId != null && fromComment != null && fromUserId != fromComment) {
            throw IllegalArgumentException("Drop-ID in the key header does not match the key itself")
        }
        return fromUserId ?: fromComment
    }

    private fun selfCertificationValid(master: PGPPublicKey, userId: String): Boolean {
        val sigs = master.getSignaturesForID(userId) ?: return false
        while (sigs.hasNext()) {
            val sig = sigs.next() as PGPSignature
            if (sig.keyID != master.keyID) continue
            try {
                sig.init(JcaPGPContentVerifierBuilderProvider().setProvider(bcProvider), master)
                if (sig.verifyCertification(userId, master)) return true
            } catch (_: Exception) {
                // try next signature
            }
        }
        return false
    }

    /**
     * Validate a pasted peer key block and return what the app needs to store.
     * Throws IllegalArgumentException if there is no usable encryption key.
     */
    fun parsePeerKey(armored: String): PeerKeyInfo {
        val trimmed = armored.trim()
        return peerKeyInfoCache.getOrPut(trimmed) {
            readPublicKey(trimmed)                       // throws if no usable encryption key
            PeerKeyInfo(trimmed, getFingerprint(trimmed), extractDropId(trimmed))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Encrypt (sign-then-encrypt)
    // ---------------------------------------------------------------------------------------

    private fun findSigningKey(privateKeyArmored: String): Pair<PGPSecretKey, PGPPrivateKey> {
        val cached = signingKeyCache[privateKeyArmored]
        if (cached != null) return cached

        val privStream = PGPUtil.getDecoderStream(ByteArrayInputStream(privateKeyArmored.toByteArray(Charsets.UTF_8)))
        val ringCollection = PGPSecretKeyRingCollection(privStream, fingerprintCalc())
        val decryptor = JcePBESecretKeyDecryptorBuilder().setProvider(bcProvider).build("".toCharArray())
        for (ring in ringCollection) {
            for (secKey in ring) {
                if (secKey.isSigningKey) {
                    val pair = Pair(secKey, secKey.extractPrivateKey(decryptor))
                    signingKeyCache[privateKeyArmored] = pair
                    return pair
                }
            }
        }
        throw IllegalArgumentException("No signing key found in private key block")
    }

    /**
     * Encrypt [data] for the recipient and sign it with our own key. Signing is mandatory: the
     * signature (SHA-256) lives inside the encrypted message.
     */
    fun encrypt(data: ByteArray, recipientArmoredPubKey: String, senderPrivateKeyArmored: String): String {
        val recipientKey = readPublicKey(recipientArmoredPubKey)
        val (signingSecret, signingPrivate) = findSigningKey(senderPrivateKeyArmored)

        val sigGen = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(signingSecret.publicKey.algorithm, HashAlgorithmTags.SHA256)
                .setProvider(bcProvider)
        )
        sigGen.init(PGPSignature.BINARY_DOCUMENT, signingPrivate)
        val sigSubpackets = PGPSignatureSubpacketGenerator()
        sigSubpackets.setIssuerFingerprint(false, signingSecret)
        sigGen.setHashedSubpackets(sigSubpackets.generate())

        val encDataGen = PGPEncryptedDataGenerator(
            JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(secureRandom)
                .setProvider(bcProvider)
        )
        encDataGen.addMethod(
            JcePublicKeyKeyEncryptionMethodGenerator(recipientKey).setProvider(bcProvider)
        )

        val out = ByteArrayOutputStream()
        val armoredOut = ArmoredOutputStream(out)
        val encOut = encDataGen.open(armoredOut, ByteArray(4096))

        sigGen.generateOnePassVersion(false).encode(encOut)

        val litDataGen = PGPLiteralDataGenerator()
        val litOut = litDataGen.open(
            encOut,
            PGPLiteralData.BINARY,
            PGPLiteralData.CONSOLE,
            data.size.toLong(),
            Date()
        )
        litOut.write(data)
        sigGen.update(data)
        litOut.close()

        sigGen.generate().encode(encOut)

        encOut.close()
        armoredOut.close()

        return out.toString(Charsets.UTF_8.name())
    }

    // ---------------------------------------------------------------------------------------
    // Decrypt + verify
    // ---------------------------------------------------------------------------------------

    private val acceptedSignatureHashes = setOf(
        HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA384, HashAlgorithmTags.SHA512
    )

    /**
     * Decrypt an armored OpenPGP message with our private key and check the embedded signature
     * against [peerPublicKeyArmored] (the stored key of the person we expect to hear from).
     *
     * Never throws because of a bad *signature*: that is reported as UNSIGNED or
     * INVALID_SIGNATURE so the UI can warn instead of silently dropping the message.
     */
    fun decrypt(
        armoredMessage: String,
        privateKeyArmored: String,
        peerPublicKeyArmored: String?
    ): DecryptedMessage {
        val privRingCollection = secretRingCollectionCache.getOrPut(privateKeyArmored) {
            val privStream = PGPUtil.getDecoderStream(ByteArrayInputStream(privateKeyArmored.toByteArray(Charsets.UTF_8)))
            PGPSecretKeyRingCollection(privStream, fingerprintCalc())
        }

        val msgStream = PGPUtil.getDecoderStream(ByteArrayInputStream(armoredMessage.toByteArray(Charsets.UTF_8)))
        val pgpFact = PGPObjectFactory(msgStream, fingerprintCalc())

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

        val decryptor = JcePBESecretKeyDecryptorBuilder().setProvider(bcProvider).build("".toCharArray())

        var privKey: PGPPrivateKey? = null
        var pbe: PGPPublicKeyEncryptedData? = null
        for (item in encList) {
            if (item is PGPPublicKeyEncryptedData) {
                val secKey = privRingCollection.getSecretKey(item.keyID)
                if (secKey != null) {
                    try {
                        privKey = secKey.extractPrivateKey(decryptor)
                        pbe = item
                        break
                    } catch (_: Exception) {
                        // keep looking
                    }
                }
            }
        }
        if (pbe == null || privKey == null) {
            throw IllegalArgumentException("Could not find matching private key to decrypt payload")
        }

        val clearStream: InputStream = pbe.getDataStream(
            JcePublicKeyDataDecryptorFactoryBuilder().setProvider(bcProvider).build(privKey)
        )

        var plainFact = PGPObjectFactory(clearStream, fingerprintCalc())
        var onePass: PGPOnePassSignature? = null
        var signature: PGPSignature? = null
        var literal: ByteArray? = null

        // Walk the decrypted packet stream: [compressed] -> [one-pass sig] -> literal -> [signature]
        var plainObj = plainFact.nextObject()
        while (plainObj != null) {
            when (plainObj) {
                is PGPCompressedData -> {
                    plainFact = PGPObjectFactory(plainObj.dataStream, fingerprintCalc())
                }
                is PGPOnePassSignatureList -> {
                    if (plainObj.size() > 0) onePass = plainObj.get(0)
                }
                is PGPLiteralData -> {
                    literal = plainObj.inputStream.readBytes()
                }
                is PGPSignatureList -> {
                    if (plainObj.size() > 0) signature = plainObj.get(0)
                }
            }
            plainObj = plainFact.nextObject()
        }

        if (literal == null) {
            throw IllegalArgumentException("Expected literal data in decrypted stream")
        }

        // Integrity (MDC) check: only valid after the stream was fully consumed.
        if (pbe.isIntegrityProtected && !pbe.verify()) {
            throw IllegalArgumentException("Message integrity check failed (modified or corrupted)")
        }

        var status = SignatureStatus.UNSIGNED
        var signer: String? = null

        val sig = signature
        val ops = onePass
        if (sig != null || ops != null) {
            status = SignatureStatus.INVALID_SIGNATURE
            if (sig != null && ops != null && peerPublicKeyArmored != null) {
                try {
                    var peerRing: PGPPublicKeyRing? = null
                    for (ring in readPublicRings(peerPublicKeyArmored)) {
                        if (ring.getPublicKey(ops.keyID) != null) {
                            peerRing = ring
                            break
                        }
                    }
                    if (peerRing != null) {
                        val signingKey = peerRing.getPublicKey(ops.keyID)
                        if (!signingKey.isRevoked &&
                            sig.keyID == ops.keyID &&
                            sig.signatureType == PGPSignature.BINARY_DOCUMENT &&
                            sig.hashAlgorithm in acceptedSignatureHashes
                        ) {
                            ops.init(JcaPGPContentVerifierBuilderProvider().setProvider(bcProvider), signingKey)
                            ops.update(literal)
                            if (ops.verify(sig)) {
                                status = SignatureStatus.VERIFIED
                                signer = hex(peerRing.publicKey.fingerprint, upper = true)
                            }
                        }
                    }
                } catch (_: Exception) {
                    status = SignatureStatus.INVALID_SIGNATURE
                    signer = null
                }
            }
        }

        return DecryptedMessage(literal, status, signer)
    }

    // ---------------------------------------------------------------------------------------
    // Safety code
    // ---------------------------------------------------------------------------------------

    /**
     * Unified 30-digit Safety Code. Both sides compute it from the two key fingerprints (order
     * independent) and compare it out-of-band, which helps detect a swapped public key.
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
