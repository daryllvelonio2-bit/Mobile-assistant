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
        seedDigest()
        seedTriage()
        checkActiveSessions()
    }

    /** R4: seed the triage queue with whatever is already sitting in the shade. */
    private fun seedTriage() {
        runCatching {
            val active = activeNotifications ?: return
            active.forEach { triage(it) }
            AppDebugServer.log(
                "TRIAGE",
                "Triage queue seeded: ${NotificationTriageEngine.unreadCount()} pending",
            )
        }.onFailure { e ->
            AppDebugServer.log("TRIAGE", "Triage seed failed: ${e.message}")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        recordDigest(sbn)
        triage(sbn)
        extractMediaInfo(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn ?: return
        sbn.key?.let { NotificationDigest.onRemoved(it) }
        sbn.key?.let { NotificationTriageEngine.onRemoved(it, sbn.packageName) }
        if (sbn.packageName == MusicTracker.currentTrack?.app) {
            MusicTracker.onAppNotificationRemoved(sbn.packageName)
        }
    }

    /**
     * R4 (Phase 4): delegate non-media notifications to the single shared triage
     * engine. Media/ongoing/group-summary noise is filtered by the engine itself.
     */
    private fun triage(sbn: StatusBarNotification) {
        runCatching {
            val extras = sbn.notification.extras
            val flags = sbn.notification.flags
            val entry = NotificationTriageEngine.onPosted(
                packageName = sbn.packageName,
                key = sbn.key ?: "${sbn.packageName}:${sbn.id}",
                title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                category = sbn.notification.category,
                isOngoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
                isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
                isMedia = isMediaNotification(sbn),
                appLabel = appLabel(sbn.packageName),
                postedAt = sbn.postTime,
            )
            if (entry != null) {
                AppDebugServer.log(
                    "TRIAGE",
                    "queued [${entry.category.id}] ${entry.appLabel}: ${entry.title} " +
                        "(crucial=${entry.crucial}, total=${NotificationTriageEngine.unreadCount()})",
                )
            }
        }.onFailure { e ->
            AppDebugServer.log("TRIAGE", "triage failed: ${e.message}")
        }
    }

    /** Media ping = transport category, a music-ish package, or an attached MediaSession. */
    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.notification.category == Notification.CATEGORY_TRANSPORT) return true
        if (isMusicApp(sbn.packageName)) return true
        val extras = sbn.notification.extras ?: return false
        return extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    }

    /** Phase 1: seed the briefing digest with everything already in the shade. */
    private fun seedDigest() {
        runCatching {
            val active = activeNotifications ?: return
            NotificationDigest.seed(active.mapNotNull { digestEntry(it) })
            AppDebugServer.log("BRIEFING", "Notification digest seeded: ${NotificationDigest.unreadCount()} active")
        }.onFailure { e ->
            AppDebugServer.log("BRIEFING", "Digest seed failed: ${e.message}")
        }
    }

    private fun recordDigest(sbn: StatusBarNotification) {
        runCatching {
            digestEntry(sbn)?.let {
                NotificationDigest.onPosted(it)
                AppDebugServer.log("BRIEFING", "Unread ping: ${it.appLabel}")
            }
        }
    }

    /** Builds a digest entry, or null for our own app / ongoing (foreground service) pings. */
    private fun digestEntry(sbn: StatusBarNotification): DigestNotification? {
        if (sbn.packageName == packageName) return null
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        val extras = sbn.notification.extras
        return DigestNotification(
            key = sbn.key ?: "${sbn.packageName}:${sbn.id}",
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            postedAt = sbn.postTime,
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

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
