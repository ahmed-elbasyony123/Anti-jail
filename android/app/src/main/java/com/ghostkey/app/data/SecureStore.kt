package com.ghostkey.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.ghostkey.app.crypto.PgpKeyPairArmored
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PeerIdentity(
    val publicKeyArmored: String,
    val fingerprint: String,
    val dropId: String
)

data class StoredIdentity(
    val mine: PgpKeyPairArmored,
    val peer: PeerIdentity?
)

/**
 * Keeps the identity (my keys, Drop-ID, read token, and the saved peer key) on disk, encrypted
 * with an AES-256-GCM key that lives in the AndroidKeyStore. The key never leaves the keystore
 * (on devices with a secure element or TEE it is hardware-backed), so the file in filesDir is
 * useless without it. Deleting the key alias therefore also makes the file unreadable.
 *
 * Messages are NOT stored here; they stay in memory only.
 */
class SecureStore(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ghostkey_identity_v2"
        private const val FILE_NAME = "identity.v2.enc"
        private const val FORMAT_VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val AAD = "ghostkey-identity-v1".toByteArray(Charsets.UTF_8)
    }

    private val atomicFile: AtomicFile get() = AtomicFile(File(context.filesDir, FILE_NAME))

    fun hasIdentity(): Boolean = File(context.filesDir, FILE_NAME).exists()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun save(identity: StoredIdentity) {
        val json = JSONObject()
            .put("privateKeyArmored", identity.mine.privateKeyArmored)
            .put("publicKeyArmored", identity.mine.publicKeyArmored)
            .put("fingerprint", identity.mine.fingerprint)
            .put("dropId", identity.mine.dropId)
            .put("readToken", identity.mine.readToken)
        identity.peer?.let {
            json.put("peerPublicKeyArmored", it.publicKeyArmored)
            json.put("peerFingerprint", it.fingerprint)
            json.put("peerDropId", it.dropId)
        }
        val plain = json.toString().toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plain)
        val iv = cipher.iv

        val file = atomicFile
        val out = file.startWrite()
        try {
            out.write(byteArrayOf(FORMAT_VERSION))
            out.write(iv)
            out.write(encrypted)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    /** Throws if the file is missing, corrupt, or its key is gone. */
    @Synchronized
    fun load(): StoredIdentity {
        val data = atomicFile.readFully()
        require(data.size > 1 + IV_BYTES + 16 && data[0] == FORMAT_VERSION) { "Unsupported identity file" }
        val iv = data.copyOfRange(1, 1 + IV_BYTES)
        val body = data.copyOfRange(1 + IV_BYTES, data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        val json = JSONObject(String(cipher.doFinal(body), Charsets.UTF_8))

        val mine = PgpKeyPairArmored(
            publicKeyArmored = json.getString("publicKeyArmored"),
            privateKeyArmored = json.getString("privateKeyArmored"),
            fingerprint = json.getString("fingerprint"),
            dropId = json.getString("dropId"),
            readToken = json.getString("readToken")
        )
        val peer = if (json.has("peerPublicKeyArmored")) {
            PeerIdentity(
                publicKeyArmored = json.getString("peerPublicKeyArmored"),
                fingerprint = json.optString("peerFingerprint", ""),
                dropId = json.optString("peerDropId", "")
            )
        } else null
        return StoredIdentity(mine, peer)
    }

    /** Delete the keystore key and the encrypted files. Safe to call when nothing exists. */
    @Synchronized
    fun wipe() {
        try {
            atomicFile.delete()
        } catch (_: Exception) {
        }
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
        }
    }
}
