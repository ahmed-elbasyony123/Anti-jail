package com.ghostkey.app.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Looks for the SOCKS port that Orbot reports in its status broadcast
 * (org.torproject.android.intent.action.STATUS / ...extra.SOCKS_PROXY_PORT).
 *
 * Notes on trust and reliability:
 * - Any app can send a broadcast with this action, so only loopback hosts and unprivileged
 *   ports are accepted, and the value is just a hint; the default port 9050 is the fallback.
 * - Whether a given Orbot version sends this broadcast to other apps is not guaranteed.
 *   When nothing arrives the client keeps using the last known port (or 9050).
 */
class OrbotPortWatcher(
    private val context: Context,
    private val onPort: (Int) -> Unit
) {
    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
        const val EXTRA_SOCKS_PROXY_HOST = "org.torproject.android.intent.extra.SOCKS_PROXY_HOST"
        const val EXTRA_SOCKS_PROXY_PORT = "org.torproject.android.intent.extra.SOCKS_PROXY_PORT"
        const val DEFAULT_SOCKS_PORT = 9050

        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

        /** Returns the port if the broadcast data is acceptable, otherwise null. */
        fun acceptablePort(host: String?, port: Int): Int? {
            if (host != null && host !in LOOPBACK_HOSTS) return null
            return if (port in 1024..65535) port else null
        }
    }

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STATUS) return
            if (!intent.hasExtra(EXTRA_SOCKS_PROXY_PORT)) return

            // Handle EXTRA_SOCKS_PROXY_PORT whether delivered as an Int, String, or other representation
            val rawPort = intent.getIntExtra(EXTRA_SOCKS_PROXY_PORT, -1).let { intPort ->
                if (intPort != -1) {
                    intPort
                } else {
                    intent.getStringExtra(EXTRA_SOCKS_PROXY_PORT)?.toIntOrNull()
                        ?: (intent.extras?.get(EXTRA_SOCKS_PROXY_PORT)?.toString()?.toIntOrNull() ?: -1)
                }
            }

            val port = acceptablePort(
                intent.getStringExtra(EXTRA_SOCKS_PROXY_HOST),
                rawPort
            ) ?: return
            onPort(port)
        }
    }

    fun start() {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_STATUS),
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
        requestStatus()
    }

    /** Asks Orbot to (re)send its status. Harmless if this Orbot version ignores it. */
    fun requestStatus() {
        try {
            context.sendBroadcast(Intent(ACTION_STATUS).setPackage(ORBOT_PACKAGE))
        } catch (_: Exception) {
            // Orbot not installed or broadcast refused: fall back to the default port.
        }
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        registered = false
    }
}
