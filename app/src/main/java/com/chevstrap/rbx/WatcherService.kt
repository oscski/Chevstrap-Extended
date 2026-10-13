package com.chevstrap.rbx

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Holder foreground service for the Roblox activity watcher session.
 *
 * The watcher reads Roblox logs and queries game APIs while the Roblox
 * game is in the foreground, i.e. while Chevstrap itself is in the
 * background. Without a foreground service, the OS (Data Saver, battery
 * optimization, OEM background restrictions) treats our UID as background
 * and blocks its network access, so DNS and API calls fail even though
 * everything works while Chevstrap is visible.
 *
 * The service performs no work itself; it only keeps the UID in a
 * foreground state for as long as a watcher session runs.
 */
class WatcherService : Service() {

    @Volatile
    private var fgStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        fgStarted = false
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    private fun startFg() {
        if (fgStarted) return
        try {
            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                )
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.chevstrap_icon)
                .setContentTitle(
                    getString(R.string.notification_watcher_title)
                )
                .setContentText(
                    getString(R.string.notification_watcher_running)
                )
                .setOngoing(true)
                .setAutoCancel(false)
                .build()

            val serviceType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                serviceType
            )

            fgStarted = true
            App.logger.writeLine(TAG, "Watcher foreground service started")
        } catch (e: Exception) {
            App.logger.writeLine(TAG, "Failed to start watcher foreground service")
            App.logger.writeException(TAG, e)
        }
    }

    companion object {
        private const val TAG = "WatcherService"
        private const val NOTIFICATION_ID = 1000
        private const val CHANNEL_ID = "chevstrap_watcher"
        private const val CHANNEL_NAME = "Activity Watcher"

        @JvmStatic
        fun start(context: Context?) {
            if (context == null) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WatcherService::class.java)
                )
            } catch (e: Exception) {
                App.logger.writeLine(TAG, "Failed to request watcher service start")
                App.logger.writeException(TAG, e)
            }
        }

        @JvmStatic
        fun stop(context: Context?) {
            if (context == null) return
            try {
                context.stopService(Intent(context, WatcherService::class.java))
                App.logger.writeLine(TAG, "Watcher foreground service stopped")
            } catch (e: Exception) {
                App.logger.writeException(TAG, e)
            }
        }
    }
}
