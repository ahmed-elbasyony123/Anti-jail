package com.ghostkey.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ghostkey.app.MainActivity
import com.ghostkey.app.notify.PushNotificationManager

/**
 * Optional keep-alive: shows a persistent notification and runs as a foreground service so
 * Android is less likely to kill the process (and with it the relay WebSocket) while the app
 * is in the background. The connection itself still lives in the app; this service only holds
 * the process alive. It is off unless the user enables it in Settings.
 */
class TorForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TorForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TorForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PushNotificationManager.init(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, PushNotificationManager.CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Sync Service")
            .setContentText("Background sync active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open)
            .build()
    }
}
