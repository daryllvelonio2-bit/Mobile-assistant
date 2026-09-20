package com.shiina.mobile.observation

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import com.shiina.mobile.debug.AppDebugServer

data class MusicTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val isPlaying: Boolean = true,
    val app: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Tracks the exact currently playing music track and artist across media players.
 * Combines active MediaSession queries, NotificationListener events, and broadcast receivers.
 */
class MusicTracker(private val context: Context) {

    private var receiverRegistered = false

    private val musicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val action = intent.action ?: return
            val track = intent.getStringExtra("track")
                ?: intent.getStringExtra("title")
                ?: intent.getStringExtra("trackName")
                ?: intent.getStringExtra("com.amazon.mp3.track")
                ?: ""
            val artist = intent.getStringExtra("artist")
                ?: intent.getStringExtra("artistName")
                ?: intent.getStringExtra("com.amazon.mp3.artist")
                ?: ""
            val album = intent.getStringExtra("album")
                ?: intent.getStringExtra("albumName")
                ?: intent.getStringExtra("com.amazon.mp3.album")
                ?: ""
            val playing = intent.getBooleanExtra("playing", true)
                || intent.getBooleanExtra("playstate", false)
                || intent.getBooleanExtra("isPlaying", true)

            if (track.isNotBlank()) {
                updateTrack(
                    title = track,
                    artist = artist,
                    album = album,
                    isPlaying = playing,
                    app = intent.`package` ?: action.substringBeforeLast(".", ""),
                )
                AppDebugServer.log("MUSIC", "Broadcast track: $track by $artist (playing=$playing)")
            }
        }
    }

    fun start() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction("com.android.music.metachanged")
            addAction("com.android.music.playstatechanged")
            addAction("com.spotify.music.metachanged")
            addAction("com.spotify.music.playbackstatechanged")
            addAction("com.android.mediacenter.metachanged")
            addAction("com.android.mediacenter.playstatechanged")
            addAction("com.htc.music.metachanged")
            addAction("fm.last.android.metachanged")
            addAction("com.sec.android.app.music.metachanged")
            addAction("com.nullsoft.winamp.metachanged")
            addAction("com.amazon.mp3.metachanged")
            addAction("com.miui.player.metachanged")
            addAction("com.real.IMP.metachanged")
            addAction("com.sonyericsson.music.metachanged")
            addAction("com.rdio.android.metachanged")
            addAction("com.samsung.sec.android.MusicPlayer.metachanged")
            addAction("com.andrew.apollo.metachanged")
            addAction("net.jjc1138.android.scrobbler.action.MUSIC_STATUS")
            addAction("com.adam.aslfms.notify.playstatechanged")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(musicReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(musicReceiver, filter)
            }
            receiverRegistered = true
            AppDebugServer.log("MUSIC", "MusicTracker broadcast receiver registered")
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "Failed to register MusicTracker receiver: ${e.message}")
        }
    }

    fun stop() {
        if (!receiverRegistered) return
        runCatching {
            context.unregisterReceiver(musicReceiver)
            receiverRegistered = false
        }
    }

    /**
     * Queries the exact currently playing music track.
     * Tries active MediaSessions first, then falls back to cached broadcast/notification track.
     */
    fun getExactMusic(): MusicTrack? {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val isMusicActive = audioManager?.isMusicActive ?: false

        // 1. Check MediaSessionManager if notification listener access is enabled
        val sessionTrack = queryActiveMediaSession()
        if (sessionTrack != null) {
            return sessionTrack
        }

        // 2. Fall back to cached track from notifications or broadcasts
        val cached = currentTrack
        if (cached != null) {
            val age = System.currentTimeMillis() - cached.timestamp
            // If audio is actively playing or the track was updated within last 5 minutes
            if (isMusicActive || (cached.isPlaying && age < 300_000L)) {
                return cached.copy(isPlaying = isMusicActive || cached.isPlaying)
            }
        }

        // 3. If music is active but track metadata is not yet known
        if (isMusicActive) {
            return MusicTrack(
                title = "Playing",
                artist = "Unknown Artist",
                isPlaying = true,
            )
        }

        return null
    }

    private fun queryActiveMediaSession(): MusicTrack? {
        return runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
            val component = ComponentName(context, MusicNotificationListener::class.java)
            val controllers = msm.getActiveSessions(component)
            for (controller in controllers) {
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                    val metadata = controller.metadata
                    val desc = controller.metadata?.description
                    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        ?: desc?.title?.toString()
                    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: desc?.subtitle?.toString()
                    val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
                        ?: desc?.description?.toString()
                    if (!title.isNullOrBlank()) {
                        val track = MusicTrack(
                            title = title,
                            artist = artist.orEmpty(),
                            album = album.orEmpty(),
                            isPlaying = true,
                            app = controller.packageName ?: "",
                        )
                        updateTrack(track.title, track.artist, track.album, true, track.app)
                        return track
                    }
                }
            }
            null
        }.getOrNull()
    }

    companion object {
        @Volatile
        var currentTrack: MusicTrack? = null
            private set

        fun updateTrack(title: String, artist: String, album: String = "", isPlaying: Boolean = true, app: String = "") {
            currentTrack = MusicTrack(
                title = title.trim(),
                artist = artist.trim(),
                album = album.trim(),
                isPlaying = isPlaying,
                app = app.trim(),
                timestamp = System.currentTimeMillis(),
            )
        }

        fun onAppNotificationRemoved(pkg: String) {
            val cur = currentTrack
            if (cur != null && cur.app == pkg) {
                currentTrack = cur.copy(isPlaying = false, timestamp = System.currentTimeMillis())
            }
        }
    }
}
