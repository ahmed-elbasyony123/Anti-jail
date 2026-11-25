package com.ghostkey.app.security

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.ghostkey.app.data.IdentityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coordinates App Lock, PIN / Duress PIN verification, Clipboard auto-clear, and Panic Wipe.
 */
internal class SecurityLockManager(
    private val context: Context,
    private val identityRepo: IdentityRepository
) {
    var appPinHash: String? = null
    var duressPinHash: String? = null
    var pinSaltHex: String? = null

    val hasAppPin: Boolean get() = !appPinHash.isNullOrBlank()
    val hasDuressPin: Boolean get() = !duressPinHash.isNullOrBlank()

    fun loadFromPrefs(prefsAppPin: String?, prefsDuressPin: String?, prefsSalt: String?) {
        appPinHash = prefsAppPin
        duressPinHash = prefsDuressPin
        pinSaltHex = prefsSalt
    }

    fun mergeFromStoredIdentity(storedAppPin: String?, storedDuressPin: String?, storedSalt: String?) {
        if (!storedSalt.isNullOrBlank()) pinSaltHex = storedSalt
        if (!storedAppPin.isNullOrBlank()) appPinHash = storedAppPin
        if (!storedDuressPin.isNullOrBlank()) duressPinHash = storedDuressPin
    }

    fun configureOnboardingPins(
        appPin: String?,
        duressPin: String?,
        ephemeralMode: Boolean
    ): Triple<String?, String?, String?> {
        val newSalt = if (!ephemeralMode && (!appPin.isNullOrBlank() || !duressPin.isNullOrBlank())) {
            identityRepo.ensurePinSalt(pinSaltHex)
        } else null
        val newAppHash = if (!appPin.isNullOrBlank()) identityRepo.hashPin(appPin, newSalt) else null
        val newDuressHash = if (!duressPin.isNullOrBlank()) identityRepo.hashPin(duressPin, newSalt) else null
        pinSaltHex = newSalt
        appPinHash = newAppHash
        duressPinHash = newDuressHash
        return Triple(newAppHash, newDuressHash, newSalt)
    }

    fun setAppPin(pin: String?, ephemeralMode: Boolean): Boolean {
        val salt = if (pin != null) identityRepo.ensurePinSalt(pinSaltHex) else pinSaltHex
        val hash = if (pin != null) identityRepo.hashPin(pin, salt) else null
        pinSaltHex = salt
        appPinHash = hash
        val newLockEnabled = hash != null
        identityRepo.savePinsToPrefs(hash, duressPinHash, salt, newLockEnabled, ephemeralMode)
        return newLockEnabled
    }

    fun setDuressPin(pin: String?, lockEnabled: Boolean, ephemeralMode: Boolean) {
        val salt = if (pin != null) identityRepo.ensurePinSalt(pinSaltHex) else pinSaltHex
        val hash = if (pin != null) identityRepo.hashPin(pin, salt) else null
        pinSaltHex = salt
        duressPinHash = hash
        identityRepo.savePinsToPrefs(appPinHash, hash, salt, lockEnabled, ephemeralMode)
    }

    fun verifyPin(pin: String): Boolean {
        if (!duressPinHash.isNullOrBlank() && identityRepo.verifyPinConstantTime(pin, duressPinHash, pinSaltHex)) {
            return false
        }
        if (!appPinHash.isNullOrBlank() && identityRepo.verifyPinConstantTime(pin, appPinHash, pinSaltHex)) {
            return true
        }
        return appPinHash.isNullOrBlank() && duressPinHash.isNullOrBlank()
    }

    fun isDuressPin(pin: String): Boolean =
        !duressPinHash.isNullOrBlank() && identityRepo.verifyPinConstantTime(pin, duressPinHash, pinSaltHex)

    fun hasDeviceKeyguard(): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true

    fun copySensitiveWithAutoClear(label: String, value: String, scope: CoroutineScope) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        scope.launch {
            delay(45_000L)
            runCatching {
                if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == value) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    fun clearClipboardAndSecrets() {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        appPinHash = null
        duressPinHash = null
        pinSaltHex = null
        identityRepo.wipeAllSync()
    }
}
