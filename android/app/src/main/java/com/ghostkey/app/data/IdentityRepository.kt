package com.ghostkey.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ghostkey.app.audio.AudioRecorderManager
import com.ghostkey.app.crypto.PgpKeyPairArmored
import com.ghostkey.app.crypto.StandalonePgpEngine
import com.ghostkey.app.util.GhostKeyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal data class AppPreferencesSnapshot(
    val relayUrl: String,
    val useTorSocks: Boolean,
    val keepAlive: Boolean,
    val orbotSocksPort: Int,
    val ephemeralMode: Boolean,
    val lockEnabled: Boolean,
    val hasCompletedOnboarding: Boolean,
    val userAlias: String,
    val appPinHash: String?,
    val duressPinHash: String?,
    val pinSaltHex: String?,
    val offlineBleMeshEnabled: Boolean = false,
    val offlineWifiDirectEnabled: Boolean = false,
    val languageCode: String = "en",
    val themeMode: String = "light"
)

/**
 * Manages identity persistence in [SecureStore] (AES-256-GCM backed by AndroidKeyStore),
 * preference storage, and salted PBKDF2 PIN hashing.
 */
internal class IdentityRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("ghostkey_prefs", Context.MODE_PRIVATE)
    private val secureStore = SecureStore(appContext)
    private val persistExecutor = Executors.newSingleThreadExecutor()
    private val persistDispatcher = persistExecutor.asCoroutineDispatcher()
    private val secureRandom = SecureRandom()

    companion object {
        private const val TAG = "IdentityRepository"
        private const val LEGACY_PIN_SALT = "ghostkey-pin-salt-v2"
        private const val PBKDF2_ITERATIONS = 65_536
        private const val PBKDF2_KEY_BITS = 256
    }

    fun readPreferences(defaultSocksPort: Int): AppPreferencesSnapshot = AppPreferencesSnapshot(
        relayUrl = prefs.getString("relay_url", "") ?: "",
        useTorSocks = prefs.getBoolean("use_tor", true),
        keepAlive = prefs.getBoolean("keep_alive", true),
        orbotSocksPort = prefs.getInt("orbot_socks_port", defaultSocksPort),
        ephemeralMode = prefs.getBoolean("ephemeral_mode", false),
        lockEnabled = prefs.getBoolean("lock_enabled", false),
        hasCompletedOnboarding = prefs.getBoolean("has_completed_onboarding", false),
        userAlias = prefs.getString("user_alias", "") ?: "",
        appPinHash = prefs.getString("app_pin_hash", null),
        duressPinHash = prefs.getString("duress_pin_hash", null),
        pinSaltHex = prefs.getString("pin_salt_hex", null),
        offlineBleMeshEnabled = prefs.getBoolean("offline_ble_mesh_enabled", false),
        offlineWifiDirectEnabled = prefs.getBoolean("offline_wifi_direct_enabled", false),
        languageCode = prefs.getString("ui_language", "en") ?: "en",
        themeMode = prefs.getString("ui_theme_mode", "light") ?: "light"
    )

    fun hasStoredIdentity(): Boolean = secureStore.hasIdentity()

    suspend fun loadIdentity(): StoredIdentity = withContext(persistDispatcher) {
        secureStore.load()
    }

    suspend fun saveIdentity(identity: StoredIdentity, ephemeralMode: Boolean) {
        if (ephemeralMode) return
        withContext(persistDispatcher) {
            saveIdentitySync(identity, ephemeralMode)
        }
    }

    fun saveIdentitySync(identity: StoredIdentity, ephemeralMode: Boolean) {
        if (ephemeralMode) return
        try {
            persistExecutor.submit {
                secureStore.save(identity)
            }.get(1_500L, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Identity save failed, attempting direct fallback", e)
            runCatching { secureStore.save(identity) }
        }
    }

    fun saveRelayAndAliasPrefs(relayUrl: String, alias: String, ephemeralMode: Boolean, markOnboarded: Boolean = false) {
        val editor = prefs.edit()
        if (ephemeralMode) {
            editor.remove("relay_url").remove("user_alias")
        } else {
            editor.putString("relay_url", relayUrl).putString("user_alias", alias)
        }
        if (markOnboarded) {
            editor.putBoolean("has_completed_onboarding", true)
        }
        editor.commit()
    }

    fun saveNetworkConfigPrefs(
        relayUrl: String,
        alias: String,
        useTor: Boolean,
        keepAlive: Boolean,
        ephemeralMode: Boolean,
        lockEnabled: Boolean
    ) {
        val editor = prefs.edit()
            .putBoolean("use_tor", useTor)
            .putBoolean("keep_alive", keepAlive)
            .putBoolean("ephemeral_mode", ephemeralMode)
            .putBoolean("lock_enabled", lockEnabled)
        if (ephemeralMode) {
            editor.remove("relay_url")
                .remove("user_alias")
                .remove("app_pin_hash")
                .remove("duress_pin_hash")
                .remove("pin_salt_hex")
        } else {
            editor.putString("relay_url", relayUrl)
                .putString("user_alias", alias)
                .putBoolean("has_completed_onboarding", true)
        }
        editor.commit()
    }

    fun saveOrbotPort(port: Int) {
        prefs.edit().putInt("orbot_socks_port", port).apply()
    }

    fun saveLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("lock_enabled", enabled).commit()
    }

    fun saveOfflineMeshPrefs(bleEnabled: Boolean, wifiDirectEnabled: Boolean) {
        prefs.edit()
            .putBoolean("offline_ble_mesh_enabled", bleEnabled)
            .putBoolean("offline_wifi_direct_enabled", wifiDirectEnabled)
            .commit()
    }

    fun saveUiPreferences(languageCode: String, themeMode: String) {
        prefs.edit()
            .putString("ui_language", languageCode)
            .putString("ui_theme_mode", themeMode)
            .apply()
    }

    fun saveOnboardingCompletion(
        alias: String,
        ephemeralMode: Boolean,
        lockEnabled: Boolean,
        appPinHash: String?,
        duressPinHash: String?,
        pinSaltHex: String?
    ) {
        val editor = prefs.edit()
            .putBoolean("ephemeral_mode", ephemeralMode)
            .putBoolean("has_completed_onboarding", true)
            .putBoolean("lock_enabled", lockEnabled)
        if (ephemeralMode) {
            // Zero disk footprint in ephemeral mode: never write alias or PINs to SharedPreferences
            editor.remove("user_alias")
                .remove("relay_url")
                .remove("app_pin_hash")
                .remove("duress_pin_hash")
                .remove("pin_salt_hex")
        } else {
            editor.putString("user_alias", alias)
            if (pinSaltHex != null) editor.putString("pin_salt_hex", pinSaltHex)
            if (appPinHash != null) editor.putString("app_pin_hash", appPinHash) else editor.remove("app_pin_hash")
            if (duressPinHash != null) editor.putString("duress_pin_hash", duressPinHash) else editor.remove("duress_pin_hash")
        }
        editor.commit()
    }

    fun savePinsToPrefs(appPinHash: String?, duressPinHash: String?, pinSaltHex: String?, lockEnabled: Boolean, ephemeralMode: Boolean) {
        if (ephemeralMode) return
        val editor = prefs.edit().putBoolean("lock_enabled", lockEnabled)
        if (pinSaltHex != null) editor.putString("pin_salt_hex", pinSaltHex)
        if (appPinHash != null) editor.putString("app_pin_hash", appPinHash) else editor.remove("app_pin_hash")
        if (duressPinHash != null) editor.putString("duress_pin_hash", duressPinHash) else editor.remove("duress_pin_hash")
        editor.commit()
    }

    fun ensurePinSalt(existingSaltHex: String?): String {
        if (!existingSaltHex.isNullOrBlank()) return existingSaltHex
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return GhostKeyUtils.bytesToHex(bytes)
    }

    fun hashPin(pin: String, saltHex: String?): String {
        val normalized = pin.trim()
        val salt = if (!saltHex.isNullOrBlank()) {
            saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            LEGACY_PIN_SALT.toByteArray(Charsets.UTF_8)
        }
        val spec = PBEKeySpec(normalized.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return GhostKeyUtils.bytesToHex(hash).also { hash.fill(0) }
    }

    fun verifyPinConstantTime(pin: String, expectedHashHex: String?, saltHex: String?): Boolean {
        if (expectedHashHex.isNullOrBlank()) return false
        val computed = hashPin(pin, saltHex)
        return MessageDigest.isEqual(
            computed.toByteArray(Charsets.US_ASCII),
            expectedHashHex.toByteArray(Charsets.US_ASCII)
        )
    }

    suspend fun wipeStorageOnly() {
        withContext(persistDispatcher) {
            secureStore.wipe()
        }
    }

    fun wipeAllSync() {
        try { persistDispatcher.close() } catch (_: Exception) {}
        StandalonePgpEngine.clearCache()
        AudioRecorderManager.purgeOrphanedCacheFiles(appContext)
        secureStore.wipe()
        prefs.edit().clear().commit()
    }

    suspend fun generateFreshIdentity(alias: String?): PgpKeyPairArmored =
        withContext(Dispatchers.Default) {
            StandalonePgpEngine.generateKeyPair(alias)
        }

    fun exportEncryptedBackup(identity: StoredIdentity, passphrase: String): String =
        secureStore.exportEncryptedBackup(identity, passphrase)

    fun importEncryptedBackup(backupJson: String, passphrase: String): StoredIdentity =
        secureStore.importEncryptedBackup(backupJson, passphrase)
}
