package com.ghostkey.app.data

import android.content.ContentResolver
import android.net.Uri
import com.ghostkey.app.crypto.GroupCrypto
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.media.MediaSanitizer
import com.ghostkey.app.util.GhostKeyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vault coordinating local PGP identity, E2EE profile picture, contacts (with key-change warning),
 * group membership / key rotation, and passphrase-encrypted identity backups.
 */
internal class IdentityAndContactsVault(
    private val contentResolver: ContentResolver,
    private val identityRepo: IdentityRepository
) {
    companion object {
        private const val MAX_AVATAR_PICK_BYTES = 8 * 1024 * 1024
    }

    suspend fun loadSanitizedAvatar(uri: Uri): Pair<String, String>? = withContext(Dispatchers.IO) {
        val raw = contentResolver.openInputStream(uri)?.use {
            GhostKeyUtils.readStreamLimited(it, MAX_AVATAR_PICK_BYTES)
        } ?: return@withContext null

        val sanitized = MediaSanitizer.sanitizeAvatar(raw) ?: return@withContext null
        sanitized.base64 to sanitized.sha256Hex
    }

    /**
     * Inserts or updates a contact while flagging if an existing contact's PGP fingerprint changed.
     */
    fun upsertContact(contacts: List<Contact>, incoming: Contact): List<Contact> {
        val idx = contacts.indexOfFirst { it.dropId == incoming.dropId }
        if (idx < 0) return contacts + incoming
        val existing = contacts[idx]
        val fpChanged = existing.fingerprint.isNotBlank() &&
            incoming.fingerprint.isNotBlank() &&
            !existing.fingerprint.equals(incoming.fingerprint, ignoreCase = true)
        val merged = incoming.copy(
            isVerified = if (fpChanged) false else incoming.isVerified,
            keyChangedWarning = existing.keyChangedWarning || fpChanged,
            avatarBase64 = incoming.avatarBase64 ?: existing.avatarBase64,
            avatarHash = incoming.avatarHash ?: existing.avatarHash
        )
        return contacts.toMutableList().apply { set(idx, merged) }
    }

    fun dismissKeyChangeWarning(contacts: List<Contact>, dropId: String): List<Contact> =
        contacts.map { if (it.dropId == dropId) it.copy(keyChangedWarning = false) else it }

    fun updateContactProfile(
        contacts: List<Contact>,
        dropId: String,
        alias: String?,
        avatarBase64: String?,
        avatarHash: String?
    ): List<Contact> {
        val idx = contacts.indexOfFirst { it.dropId == dropId }
        if (idx < 0) return contacts
        val current = contacts[idx]
        val nextAlias = alias?.takeIf { it.isNotBlank() }?.let { GhostKeyUtils.sanitizeAlias(it) } ?: current.alias
        val nextAvatar = avatarBase64?.takeIf { it.isNotBlank() } ?: current.avatarBase64
        val nextHash = avatarHash?.takeIf { it.isNotBlank() } ?: current.avatarHash
        if (nextAlias == current.alias && nextHash == current.avatarHash && nextAvatar == current.avatarBase64) {
            return contacts
        }
        return contacts.toMutableList().apply {
            set(idx, current.copy(alias = nextAlias, avatarBase64 = nextAvatar, avatarHash = nextHash))
        }
    }

    fun rotateGroupKey(group: GroupChat): GroupChat =
        group.copy(sharedKeyBase64 = GroupCrypto.generateGroupKey())

    fun removeGroupMember(group: GroupChat, memberDropId: String): GroupChat =
        group.copy(members = group.members.filterNot { it.dropId == memberDropId })

    fun parseIntroducedContact(
        myKeys: PgpKeyPairArmored?,
        contacts: List<Contact>,
        alias: String,
        dropId: String,
        armoredKey: String,
        safetyCode: String?
    ): Result<Contact> {
        val cleanDropId = dropId.trim().lowercase()
        if (!StandalonePgpEngine.isValidHex32(cleanDropId)) {
            return Result.failure(IllegalArgumentException("Invalid Drop-ID in contact introduction"))
        }
        val info = runCatching { StandalonePgpEngine.parsePeerKey(armoredKey) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid PGP key in contact introduction"))
        if (info.dropId != null && info.dropId != cleanDropId) {
            return Result.failure(SecurityException("Security warning: Introduced Drop-ID does not match key!"))
        }
        if (contacts.any { it.dropId == cleanDropId || it.fingerprint == info.fingerprint }) {
            return Result.failure(IllegalStateException("Contact already exists in your address book"))
        }
        val cleanAlias = GhostKeyUtils.sanitizeAlias(alias)
        val verifiedSas = myKeys?.let { StandalonePgpEngine.generateSafetyCode(it.fingerprint, info.fingerprint) }
        val isVerified = safetyCode != null && verifiedSas != null &&
            safetyCode.replace(" ", "") == verifiedSas.replace(" ", "")
        return Result.success(
            Contact(
                id = cleanDropId,
                alias = cleanAlias.ifBlank { info.alias ?: "Peer" },
                dropId = cleanDropId,
                publicKeyArmored = info.publicKeyArmored,
                fingerprint = info.fingerprint,
                isVerified = isVerified,
                safetyCode = verifiedSas
            )
        )
    }

    fun exportBackup(snapshot: StoredIdentity, passphrase: String): Result<String> =
        runCatching { identityRepo.exportEncryptedBackup(snapshot, passphrase) }

    fun importBackup(backupJson: String, passphrase: String): Result<StoredIdentity> =
        runCatching { identityRepo.importEncryptedBackup(backupJson, passphrase) }
}
