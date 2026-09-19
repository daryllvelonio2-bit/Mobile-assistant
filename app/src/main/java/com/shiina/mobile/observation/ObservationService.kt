package com.shiina.mobile.observation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Foreground host for the usage-polling and capture loops (loops land in Phase 2). */
class ObservationService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Observation", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Shiina Mobile")
            .setContentText("Observing")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "observation"
        private const val NOTIFICATION_ID = 1
    }
}
