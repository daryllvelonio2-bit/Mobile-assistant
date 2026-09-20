package com.shiina.mobile.observation

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.shiina.mobile.debug.AppDebugServer

/**
 * NotificationListenerService that observes media playback notifications and active MediaSessions
 * to extract exact track titles and artists across music apps (Spotify, Huawei Music, YouTube Music, etc.).
 */
class MusicNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppDebugServer.log("MUSIC", "MusicNotificationListener connected")
        checkActiveSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        extractMediaInfo(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn ?: return
        if (sbn.packageName == MusicTracker.currentTrack?.app) {
            MusicTracker.onAppNotificationRemoved(sbn.packageName)
        }
    }

    private fun checkActiveSessions() {
        runCatching {
            val msm = getSystemService(MediaSessionManager::class.java) ?: return
            val component = ComponentName(this, MusicNotificationListener::class.java)
            val controllers = msm.getActiveSessions(component)
            for (controller in controllers) {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    val metadata = controller.metadata
                    val desc = controller.metadata?.description
                    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: desc?.title?.toString()
                    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: desc?.subtitle?.toString()
                    val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                        ?: desc?.description?.toString()
                    if (!title.isNullOrBlank()) {
                        MusicTracker.updateTrack(
                            title = title,
                            artist = artist.orEmpty(),
                            album = album.orEmpty(),
                            isPlaying = true,
                            app = controller.packageName,
                        )
                        AppDebugServer.log("MUSIC", "Active session found: $title by $artist")
                        return
                    }
                }
            }
        }.onFailure { e ->
            AppDebugServer.log("MUSIC", "checkActiveSessions failed: ${e.message}")
        }
    }

    private fun extractMediaInfo(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }

        if (token != null) {
            runCatching {
                val controller = MediaController(this, token)
                val metadata = controller.metadata
                val desc = controller.metadata?.description
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: desc?.title?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: desc?.subtitle?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                    ?: desc?.description?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING

                if (!title.isNullOrBlank()) {
                    MusicTracker.updateTrack(
                        title = title,
                        artist = artist.orEmpty(),
                        album = album.orEmpty(),
                        isPlaying = isPlaying,
                        app = sbn.packageName,
                    )
                    AppDebugServer.log("MUSIC", "Notification media: $title by $artist (playing=$isPlaying)")
                }
            }.onFailure { e ->
                AppDebugServer.log("MUSIC", "MediaController failed: ${e.message}")
            }
        } else {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            val category = sbn.notification.category
            if (category == Notification.CATEGORY_TRANSPORT || isMusicApp(sbn.packageName)) {
                if (!title.isNullOrBlank()) {
                    MusicTracker.updateTrack(
                        title = title,
                        artist = text.orEmpty(),
                        album = subText.orEmpty(),
                        isPlaying = true,
                        app = sbn.packageName,
                    )
                    AppDebugServer.log("MUSIC", "Transport notification: $title by $text")
                }
            }
        }
    }

    private fun isMusicApp(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("music") || p.contains("spotify") || p.contains("audio") ||
            p.contains("player") || p.contains("soundcloud") || p.contains("podcast")
    }
}
