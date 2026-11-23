package com.ghostkey.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.ghostkey.app.crypto.PgpKeyPairArmored
import org.json.JSONArray
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
    val peer: PeerIdentity? = null,
    val userAlias: String = "",
    val relayUrl: String = "",
    val contacts: List<Contact> = emptyList(),
    val groups: List<GroupChat> = emptyList(),
    val duressPinHash: String? = null
)

/**
 * Keeps the identity (my keys, Drop-ID, read token, user alias, contacts, and groups) on disk,
 * encrypted with an AES-256-GCM key that lives in the AndroidKeyStore.
 *
 * Messages are NOT stored here; they stay in memory (RAM) only.
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
            .put("userAlias", identity.userAlias)
            .put("relayUrl", identity.relayUrl)

        identity.peer?.let {
            json.put("peerPublicKeyArmored", it.publicKeyArmored)
            json.put("peerFingerprint", it.fingerprint)
            json.put("peerDropId", it.dropId)
        }
        if (!identity.duressPinHash.isNullOrBlank()) {
            json.put("duressPinHash", identity.duressPinHash)
        }

        // Serialize contacts
        val contactsArray = JSONArray()
        for (c in identity.contacts) {
            val cObj = JSONObject()
                .put("id", c.id)
                .put("alias", c.alias)
                .put("dropId", c.dropId)
                .put("publicKeyArmored", c.publicKeyArmored)
                .put("fingerprint", c.fingerprint)
                .put("addedAt", c.addedAt)
                .put("isVerified", c.isVerified)
            if (c.safetyCode != null) cObj.put("safetyCode", c.safetyCode)
            if (c.disappearingTtlSeconds != null) cObj.put("disappearingTtlSeconds", c.disappearingTtlSeconds)
            contactsArray.put(cObj)
        }
        json.put("contacts", contactsArray)

        // Serialize groups
        val groupsArray = JSONArray()
        for (g in identity.groups) {
            val membersArray = JSONArray()
            for (m in g.members) {
                membersArray.put(
                    JSONObject()
                        .put("dropId", m.dropId)
                        .put("alias", m.alias)
                        .put("publicKeyArmored", m.publicKeyArmored)
                        .put("fingerprint", m.fingerprint)
                )
            }
            groupsArray.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("groupDropId", g.groupDropId)
                    .put("groupReadToken", g.groupReadToken)
                    .put("sharedKeyBase64", g.sharedKeyBase64)
                    .put("members", membersArray)
                    .put("createdAt", g.createdAt)
                    .put("isAdmin", g.isAdmin)
            )
        }
        json.put("groups", groupsArray)

        val plain = json.toString().toByteArray(Charsets.UTF_8)
        try {
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
        } finally {
            plain.fill(0)
        }
    }

    /** Throws if the file is missing, corrupt, or its key is gone. */
    @Synchronized
    fun load(): StoredIdentity {
        val data = atomicFile.readFully()
        require(data.size > 1 + IV_BYTES + 16 && data[0] == FORMAT_VERSION) { "Unsupported identity file" }
        val iv = data.copyOfRange(1, 1 + IV_BYTES)
        val body = data.copyOfRange(1 + IV_BYTES, data.size)

        val decryptedBytes: ByteArray
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            decryptedBytes = cipher.doFinal(body)
        } finally {
            data.fill(0)
            body.fill(0)
        }

        val jsonStr = String(decryptedBytes, Charsets.UTF_8)
        decryptedBytes.fill(0)
        val json = JSONObject(jsonStr)

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

        val userAlias = json.optString("userAlias", "")

        val contactsList = mutableListOf<Contact>()
        val contactsArray = json.optJSONArray("contacts")
        if (contactsArray != null) {
            for (i in 0 until contactsArray.length()) {
                val c = contactsArray.getJSONObject(i)
                contactsList.add(
                    Contact(
                        id = c.optString("id", c.optString("dropId")),
                        alias = c.optString("alias", "Peer"),
                        dropId = c.getString("dropId"),
                        publicKeyArmored = c.getString("publicKeyArmored"),
                        fingerprint = c.optString("fingerprint", ""),
                        addedAt = c.optLong("addedAt", System.currentTimeMillis()),
                        isVerified = c.optBoolean("isVerified", false),
                        safetyCode = c.optString("safetyCode", null)?.ifBlank { null },
                        disappearingTtlSeconds = if (c.has("disappearingTtlSeconds")) c.getLong("disappearingTtlSeconds") else null
                    )
                )
            }
        }

        val groupsList = mutableListOf<GroupChat>()
        val groupsArray = json.optJSONArray("groups")
        if (groupsArray != null) {
            for (i in 0 until groupsArray.length()) {
                val g = groupsArray.getJSONObject(i)
                val membersList = mutableListOf<GroupMember>()
                val membersArray = g.optJSONArray("members")
                if (membersArray != null) {
                    for (j in 0 until membersArray.length()) {
                        val m = membersArray.getJSONObject(j)
                        membersList.add(
                            GroupMember(
                                dropId = m.getString("dropId"),
                                alias = m.optString("alias", "Member"),
                                publicKeyArmored = m.optString("publicKeyArmored", ""),
                                fingerprint = m.optString("fingerprint", "")
                            )
                        )
                    }
                }
                groupsList.add(
                    GroupChat(
                        id = g.getString("id"),
                        name = g.getString("name"),
                        groupDropId = g.getString("groupDropId"),
                        groupReadToken = g.getString("groupReadToken"),
                        sharedKeyBase64 = g.getString("sharedKeyBase64"),
                        members = membersList,
                        createdAt = g.optLong("createdAt", System.currentTimeMillis()),
                        isAdmin = g.optBoolean("isAdmin", false)
                    )
                )
            }
        }

        val duressPinHash = json.optString("duressPinHash", null)?.ifBlank { null }
        val relayUrl = json.optString("relayUrl", "")

        return StoredIdentity(mine, peer, userAlias, relayUrl, contactsList, groupsList, duressPinHash)
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
        try {
            com.ghostkey.app.crypto.StandalonePgpEngine.clearCache()
        } catch (_: Exception) {
        }
    }
}
