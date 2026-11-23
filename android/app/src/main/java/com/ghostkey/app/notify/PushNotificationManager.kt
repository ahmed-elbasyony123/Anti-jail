package com.ghostkey.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ghostkey.app.MainActivity
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages zero-leakage, high-privacy Tor push notifications for GhostKey.
 *
 * Privacy Guarantees:
 * - Operates entirely without Google Play Services (FCM) or third-party telemetry.
 * - Notifications are triggered exclusively via direct in-process Tor WebSocket / polling events.
 * - Enforces [NotificationCompat.VISIBILITY_PRIVATE] with a sanitized public fallback
 *   so sensitive sender fingerprints or message text are never exposed on secure lock screens.
 */
object PushNotificationManager {

    const val CHANNEL_MESSAGES = "ghostkey_push_messages"
    const val CHANNEL_CONNECTION = "ghostkey_connection"

    private const val NOTIFICATION_ID_MESSAGE = 9001
    private val unreadCount = AtomicInteger(0)

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // 1. High-importance channel for incoming encrypted messages
        if (manager.getNotificationChannel(CHANNEL_MESSAGES) == null) {
            val messageChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "Encrypted Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming messages over Tor"
                enableLights(true)
                lightColor = 0xFF00FF66.toInt() // Neon Emerald
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(true)
                val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setSound(
                    defaultSound,
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .build()
                )
            }
            manager.createNotificationChannel(messageChannel)
        }

        // 2. Low-importance keep-alive channel for Tor foreground sync service
        if (manager.getNotificationChannel(CHANNEL_CONNECTION) == null) {
            val connectionChannel = NotificationChannel(
                CHANNEL_CONNECTION,
                "Tor Background Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Tor onion circuits active in background"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(connectionChannel)
        }
    }

    /**
     * Posts a stealth notification when an encrypted message arrives while app is in background.
     * Strips plaintext message body from background system notifications to prevent Android
     * notification history leaks. Uses generic notifications for privacy.
     */
    fun postMessageNotification(
        context: Context,
        isAudio: Boolean,
        hasMedia: Boolean,
        previewText: String? = null
    ) {
        init(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val count = unreadCount.incrementAndGet()

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Public version shown on locked device (Zero Data Leak)
        val publicNotification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("GhostKey")
            .setContentText(if (count > 1) "$count new encrypted messages" else "New encrypted message received")
            .setColor(0xFF00FF66.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        // Content description when unlocked:
        // Strip plaintext preview to protect against Android notification history logs and lock-screen leaks
        val genericText = when {
            count > 1 -> "$count new encrypted messages"
            isAudio -> "New encrypted voice note"
            hasMedia -> "New encrypted attachment"
            else -> "New encrypted message received"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("GhostKey // Secure Relay")
            .setContentText(genericText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(genericText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setColor(0xFF00FF66.toInt())
            .setAutoCancel(true)
            .setNumber(count)
            .setContentIntent(openIntent)
            .build()

        manager.notify(NOTIFICATION_ID_MESSAGE, notification)
    }

    /**
     * Clears all message notification badges when user views or enters the app.
     */
    fun clearMessageNotifications(context: Context) {
        unreadCount.set(0)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID_MESSAGE)
    }
}
