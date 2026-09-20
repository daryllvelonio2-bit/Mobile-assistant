package com.shiina.mobile.observation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import com.shiina.mobile.CompanionApp
import com.shiina.mobile.debug.AppDebugServer

/**
 * Foreground host for capture: holds the MediaProjection from the user's
 * one-time consent and runs the CaptureWatcher (app-open / rotation / music
 * triggers, 30% roll, 15-minute cooldown). Leak-free: watcher + projection
 * torn down in onDestroy.
 */
class ObservationService : Service() {

    private var watcher: CaptureWatcher? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Observation", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Shiina Mobile")
            .setContentText("Observing")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { e ->
                AppDebugServer.log("ERROR", "Observation startForeground failed: ${e.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        val container = (application as CompanionApp).container
        if (intent?.action == ACTION_START_PROJECTION) {
            val data = CaptureConsent.data
            if (data != null) {
                runCatching {
                    val mpm = getSystemService(MediaProjectionManager::class.java)
                    container.screenshotTaker.setProjection(
                        mpm.getMediaProjection(CaptureConsent.resultCode, data),
                    )
                }.onFailure { e ->
                    AppDebugServer.log("ERROR", "Projection create failed: ${e.message}")
                }
            } else {
                AppDebugServer.log("ERROR", "Projection start missing consent data")
            }
        }
        if (watcher == null) {
            watcher = CaptureWatcher(this, container.screenshotTaker, container.settingsRepository)
                .also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { watcher?.stop() }
        watcher = null
        runCatching {
            ((application as? CompanionApp)?.container?.screenshotTaker)?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_PROJECTION = "com.shiina.mobile.observation.START_PROJECTION"
        private const val CHANNEL = "observation"
        private const val NOTIFICATION_ID = 1
    }
}
