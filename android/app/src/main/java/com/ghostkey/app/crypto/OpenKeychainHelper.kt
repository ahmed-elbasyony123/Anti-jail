package com.ghostkey.app.crypto

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.openintents.openpgp.OpenPgpError
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.openpgp.util.OpenPgpServiceConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * OpenKeychain Cryptographic Integration Bridge
 * -------------------------------------------------------------
 * 1. Connects to OpenKeychain via OpenPgpServiceConnection.
 * 2. Never handles private keys directly - keys remain safely inside OpenKeychain.
 * 3. Supports Hardware Tokens (YubiKeys) and NFC security keys out-of-the-box.
 * 4. Derives zero-knowledge Mailbox hashes for blind routing.
 */
class OpenKeychainHelper(
    private val context: Context,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    companion object {
        const val OPENKEYCHAIN_PACKAGE = "org.sufficientlysecure.keychain"
        const val REQUEST_CODE_USER_INTERACTION = 9901

        /**
         * Computes a SHA-256 hash of a recipient's public key fingerprint.
         * The relay server only ever sees this hash, preserving recipient anonymity.
         */
        fun deriveMailboxHash(fingerprintOrId: String, salt: String = "ghostkey-blind-relay-v1"): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = "$fingerprintOrId:$salt".toByteArray(StandardCharsets.UTF_8)
            val hashBytes = digest.digest(input)
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Computes a unified 30-digit Signal-style Safety Code from two PGP fingerprints.
         * Identical on both users' phones to detect and prevent Man-In-The-Middle (MITM) attacks.
         */
        fun computeSafetyCode(fp1: String, fp2: String): String {
            if (fp1.isBlank() || fp2.isBlank()) return "00000 00000 00000 00000 00000 00000"
            val clean1 = fp1.replace(" ", "").uppercase()
            val clean2 = fp2.replace(" ", "").uppercase()
            val sorted = listOf(clean1, clean2).sorted()
            val combined = "${sorted[0]}:${sorted[1]}"
            val digest = MessageDigest.getInstance("SHA-256").digest(combined.toByteArray(StandardCharsets.UTF_8))
            
            val chunks = mutableListOf<String>()
            for (i in 0 until 6) {
                val b0 = digest[i * 2].toInt() and 0xFF
                val b1 = digest[i * 2 + 1].toInt() and 0xFF
                val num = (b0 shl 8) or b1
                chunks.add(String.format(java.util.Locale.US, "%05d", num % 100000))
            }
            return chunks.joinToString(" ")
        }
    }

    private var openPgpServiceConnection: OpenPgpServiceConnection? = null
    var isBound: Boolean = false
        private set

    init {
        bindService()
    }

    fun bindService() {
        openPgpServiceConnection = OpenPgpServiceConnection(
            context.applicationContext,
            OPENKEYCHAIN_PACKAGE,
            object : OpenPgpServiceConnection.OnBound {
                override fun onBound(service: org.openintents.openpgp.IOpenPgpService2?) {
                    isBound = true
                    onConnectionChanged(true)
                }

                override fun onError(e: Exception?) {
                    isBound = false
                    onConnectionChanged(false)
                }
            }
        )
        openPgpServiceConnection?.bindToService()
    }

    fun unbindService() {
        if (isBound) {
            openPgpServiceConnection?.unbindFromService()
            isBound = false
            onConnectionChanged(false)
        }
    }

    /**
     * Encrypts plaintext using OpenKeychain with the recipient's User ID or Key IDs.
     */
    fun encrypt(
        plainText: String,
        recipientUserIds: Array<String>?,
        recipientKeyIds: LongArray?,
        activity: Activity,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isBound || openPgpServiceConnection == null) {
            onError("OpenKeychain is not connected. Make sure OpenKeychain is installed and running.")
            return
        }

        val data = Intent()
        data.action = OpenPgpApi.ACTION_ENCRYPT
        data.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, true)

        if (recipientKeyIds != null && recipientKeyIds.isNotEmpty()) {
            data.putExtra(OpenPgpApi.EXTRA_KEY_IDS, recipientKeyIds)
        }
        if (recipientUserIds != null && recipientUserIds.isNotEmpty()) {
            data.putExtra(OpenPgpApi.EXTRA_USER_IDS, recipientUserIds)
        }

        val inputStream = ByteArrayInputStream(plainText.toByteArray(StandardCharsets.UTF_8))
        val outputStream = ByteArrayOutputStream()

        val api = OpenPgpApi(context, openPgpServiceConnection!!.service)
        val result = api.executeApi(data, inputStream, outputStream)

        handleApiResult(result, activity, {
            val encryptedArmored = outputStream.toString("UTF-8")
            onSuccess(encryptedArmored)
        }, onError)
    }

    /**
     * Decrypts OpenPGP ASCII Armor ciphertext using OpenKeychain and the user's private key.
     */
    fun decrypt(
        cipherText: String,
        activity: Activity,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isBound || openPgpServiceConnection == null) {
            onError("OpenKeychain is not connected.")
            return
        }

        val data = Intent()
        data.action = OpenPgpApi.ACTION_DECRYPT_VERIFY

        val inputStream = ByteArrayInputStream(cipherText.toByteArray(StandardCharsets.UTF_8))
        val outputStream = ByteArrayOutputStream()

        val api = OpenPgpApi(context, openPgpServiceConnection!!.service)
        val result = api.executeApi(data, inputStream, outputStream)

        handleApiResult(result, activity, {
            val decryptedPlain = outputStream.toString("UTF-8")
            onSuccess(decryptedPlain)
        }, onError)
    }

    private fun handleApiResult(
        result: Intent?,
        activity: Activity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (result == null) {
            onError("Null response from OpenKeychain.")
            return
        }

        when (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
            OpenPgpApi.RESULT_CODE_SUCCESS -> {
                onSuccess()
            }
            OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED -> {
                // OpenKeychain requires user input (e.g. passphrase, PIN, or YubiKey tap)
                val pendingIntent: android.app.PendingIntent? =
                    result.getParcelableExtra(OpenPgpApi.RESULT_INTENT)
                if (pendingIntent != null) {
                    try {
                        activity.startIntentSenderForResult(
                            pendingIntent.intentSender,
                            REQUEST_CODE_USER_INTERACTION,
                            null,
                            0,
                            0,
                            0
                        )
                    } catch (e: Exception) {
                        onError("Failed to launch OpenKeychain prompt: ${e.message}")
                    }
                } else {
                    onError("User interaction required, but no prompt intent provided.")
                }
            }
            OpenPgpApi.RESULT_CODE_ERROR -> {
                val error: OpenPgpError? = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR)
                onError(error?.message ?: "Unknown OpenPGP error.")
            }
        }
    }
}
