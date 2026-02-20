package com.openclaw.assistant.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.openclaw.assistant.wear.R

/**
 * Foreground service to prevent process freeze during voice interaction on Wear OS.
 * Holds a partial wake lock while the session is active.
 */
class WearSessionForegroundService : Service() {

    companion object {
        private const val TAG = "WearFGSvc"
        private const val CHANNEL_ID = "wear_voice_session"
        private const val NOTIFICATION_ID = 2001
        private const val WAKE_LOCK_TAG = "openclaw:wear_session"

        fun start(context: Context) {
            val intent = Intent(context, WearSessionForegroundService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start foreground service", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WearSessionForegroundService::class.java)
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop foreground service", e)
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_session_title))
            .setContentText(getString(R.string.notification_session_content))
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                acquire(3 * 60 * 1000L) // 3 minutes max (shorter than phone)
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock", e)
        }
        wakeLock = null
    }
}
