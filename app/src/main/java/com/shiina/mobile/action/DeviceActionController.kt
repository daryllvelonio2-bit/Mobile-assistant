package com.shiina.mobile.action

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.observation.MusicNotificationListener
import com.shiina.mobile.observation.MusicTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes on-device actions such as media playback control, volume/audio adjustments,
 * and app launching. Bridges autonomous LLM agent decisions with real Android hardware & system services.
 */
class DeviceActionController(
    private val context: Context,
    private val screenMetrics: com.shiina.mobile.observation.ScreenMetrics? = null,
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Searches local MediaStore audio library (Downloads, Music) and returns structured candidate tracks.
     * Affordance: inspect library before deciding or playing.
     */
    fun searchMusic(query: String = ""): String {
        val rawQ = query.trim()
        val q = rawQ.replace(Regex("""^["'`“]+|["'`”.,!]+$"""), "").trim()
        AppDebugServer.log("ACTION", "Executing searchMusic: '$q'")
        val results = JSONArray()
        val isBrowseAll = q.isBlank() || q.lowercase() in setOf("all", "list", "*", "any", "recent")

        runCatching {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION,
            )

            var cursor: android.database.Cursor?
            if (isBrowseAll) {
                cursor = context.contentResolver.query(
                    uri, projection, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC",
                )
            } else {
                val selection = "${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?"
                val arg = "%$q%"
                cursor = context.contentResolver.query(
                    uri, projection, selection, arrayOf(arg, arg, arg), null,
                )

                if (cursor == null || !cursor.moveToFirst()) {
                    cursor?.close()
                    val words = q.split(Regex("""[\s\-_]+""")).map { it.trim().lowercase() }
                        .filter { it.length > 2 && it !in setOf("the", "song", "track", "play", "please", "gusto", "kanta", "official", "video", "audio") }
                    if (words.size > 1) {
                        val sb = java.lang.StringBuilder()
                        val args = mutableListOf<String>()
                        words.forEachIndexed { index, w ->
                            if (index > 0) sb.append(" AND ")
                            sb.append("(${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)")
                            args.add("%$w%")
                            args.add("%$w%")
                            args.add("%$w%")
                        }
                        cursor = context.contentResolver.query(uri, projection, sb.toString(), args.toTypedArray(), null)
                    } else {
                        cursor = null
                    }
                }
            }

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = it.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = it.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val durCol = it.getColumnIndex(MediaStore.Audio.Media.DURATION)

                var count = 0
                val limit = if (isBrowseAll) 20 else 10
                while (it.moveToNext() && count < limit) {
                    val id = it.getLong(idCol)
                    val title = if (titleCol >= 0) it.getString(titleCol) else ""
                    val artist = if (artistCol >= 0) it.getString(artistCol) else ""
                    val durSec = if (durCol >= 0) it.getLong(durCol) / 1000 else 0L
                    results.put(
                        JSONObject()
                            .put("id", id)
                            .put("title", title)
                            .put("artist", artist)
                            .put("duration_sec", durSec)
                    )
                    count++
                }
            }
        }.onFailure { e ->
            AppDebugServer.log("ACTION", "searchMusic failed: ${e.message}")
        }

        // If specific search returned 0 tracks, also fetch top available tracks from library as fallback
        val availableFallback = JSONArray()
        if (results.length() == 0 && !isBrowseAll) {
            runCatching {
                val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                )
                context.contentResolver.query(
                    uri, projection, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC",
                )?.use {
                    val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = it.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val artistCol = it.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val durCol = it.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    var count = 0
                    while (it.moveToNext() && count < 10) {
                        availableFallback.put(
                            JSONObject()
                                .put("id", it.getLong(idCol))
                                .put("title", if (titleCol >= 0) it.getString(titleCol) else "")
                                .put("artist", if (artistCol >= 0) it.getString(artistCol) else "")
                                .put("duration_sec", if (durCol >= 0) it.getLong(durCol) / 1000 else 0L)
                        )
                        count++
                    }
                }
            }
        }

        val json = JSONObject()
            .put("tool", "SEARCH_MUSIC")
            .put("query", q)
            .put("count", results.length())
            .put("tracks", results)

        if (availableFallback.length() > 0) {
            json.put("available_tracks", availableFallback)
            json.put("note", "No local tracks matched '$q'. However, here are ${availableFallback.length()} available tracks in the local library you can pick from instead, or you can play '$q' via YouTube streaming.")
        } else if (results.length() > 0) {
            json.put("note", "Found ${results.length()} matching local track(s).")
        } else {
            json.put("note", "No local tracks found. Can play via YouTube streaming.")
        }

        return json.toString()
    }

    /**
     * Controls active media playback (pause, play/resume, stop, next, prev).
     * If a specific song query is provided or action is "play <song>", delegates to playSong.
     */
    fun controlMedia(action: String, query: String = ""): String {
        val act = action.lowercase().trim()
        val q = query.trim()
        if (act in setOf("restore", "unmute", "restore_volume", "put back volume", "put_back_volume")) {
            return volumeControl("restore")
        }
        if (q.isNotEmpty() && (act == "play" || act == "play_song" || act.isEmpty())) {
            return playSong(q)
        }
        if (act.startsWith("play ") && act.length > 5) {
            return playSong(act.removePrefix("play ").trim())
        }

        AppDebugServer.log("ACTION", "Executing MEDIA_CONTROL: $act")
        var sessionControlled = false

        // 1. Try active MediaSessions via MediaSessionManager
        runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val component = ComponentName(context, MusicNotificationListener::class.java)
            val controllers = msm?.getActiveSessions(component)
            if (!controllers.isNullOrEmpty()) {
                for (controller in controllers) {
                    when (act) {
                        "pause", "stop", "off", "turn_off", "turn off" -> controller.transportControls.pause()
                        "play", "resume", "start", "on", "turn_on", "turn on" -> controller.transportControls.play()
                        "next", "skip" -> controller.transportControls.skipToNext()
                        "prev", "previous", "back" -> controller.transportControls.skipToPrevious()
                        "toggle" -> {
                            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                                controller.transportControls.pause()
                            } else {
                                controller.transportControls.play()
                            }
                        }
                    }
                    sessionControlled = true
                }
            }
        }.onFailure { e ->
            AppDebugServer.log("ACTION", "MediaSession control failed: ${e.message}")
        }

        // 2. Dispatch MediaKeyEvent via AudioManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val keyCode = when (act) {
            "pause", "off", "turn_off", "turn off" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play", "resume", "start", "on", "turn_on", "turn on" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev", "previous", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        audioManager?.let { am ->
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }

        // 3. For pause/stop/off: transiently request audio focus to immediately silence any stubborn audio stream
        if (act in setOf("pause", "stop", "off", "turn_off", "turn off")) {
            audioManager?.let { am ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).build()
                    am.requestAudioFocus(focusRequest)
                    scope.launch {
                        delay(600)
                        am.abandonAudioFocusRequest(focusRequest)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
            }
            MusicTracker.updateTrack("", "", "", isPlaying = false, app = "")
        }

        val isMusicActive = audioManager?.isMusicActive ?: false
        val statusJson = JSONObject()
            .put("tool", "MEDIA_CONTROL")
            .put("action", act)
            .put("success", true)
            .put("is_music_active", isMusicActive)
            .put("state", when (act) {
                "pause", "off", "turn_off", "turn off" -> "paused"
                "play", "resume", "start", "on", "turn_on", "turn on" -> "playing"
                "stop" -> "stopped"
                "next", "skip" -> "skipped_next"
                "prev", "previous", "back" -> "skipped_prev"
                else -> act
            })
        return statusJson.toString()
    }

    /**
     * Plays a specific song, artist, album, or playlist.
     * 1. If player is explicitly requested (youtube/spotify), routes there.
     * 2. Searches local MediaStore audio library (e.g. Download/Music folder) and plays directly in music player.
     * 3. Falls back to YouTube / streaming search in browser or app (with honest receipt).
     */
    fun playSong(query: String, player: String = ""): String {
        val rawQ = query.trim()
        val q = rawQ.replace(Regex("""^["'`“]+|["'`”.,!]+$"""), "").trim()
        if (q.isBlank()) return controlMedia("play")
        AppDebugServer.log("ACTION", "Executing playSong: '$q' (player='$player')")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // 1. Explicit player routing
        if (player.equals("youtube", ignoreCase = true)) {
            return searchApp("youtube", q)
        }
        if (player.equals("spotify", ignoreCase = true)) {
            return searchApp("spotify", q)
        }

        // 2. Search local on-device audio library (MediaStore)
        runCatching {
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ARTIST,
            )
            val selection = "${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?"
            val arg = "%$q%"
            var cursor = context.contentResolver.query(
                uri, projection, selection, arrayOf(arg, arg, arg), null,
            )

            if (cursor == null || !cursor.moveToFirst()) {
                cursor?.close()
                val words = q.split(Regex("""[\s\-_]+""")).map { it.trim().lowercase() }
                    .filter { it.length > 2 && it !in setOf("the", "song", "track", "play", "please", "gusto", "kanta", "official", "video", "audio") }
                if (words.size > 1) {
                    val sb = java.lang.StringBuilder()
                    val args = mutableListOf<String>()
                    words.forEachIndexed { index, w ->
                        if (index > 0) sb.append(" AND ")
                        sb.append("(${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ?)")
                        args.add("%$w%")
                        args.add("%$w%")
                        args.add("%$w%")
                    }
                    cursor = context.contentResolver.query(uri, projection, sb.toString(), args.toTypedArray(), null)
                } else {
                    cursor = null
                }
            }

            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    val titleCol = it.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val artistCol = it.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val title = if (titleCol >= 0) it.getString(titleCol) else q
                    val artist = if (artistCol >= 0) it.getString(artistCol) else ""
                    val contentUri = android.content.ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "audio/*")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        AppDebugServer.log("ACTION", "Playing local audio: $title (uri=$contentUri)")
                        return JSONObject()
                            .put("tool", "PLAY_MUSIC")
                            .put("status", "playing")
                            .put("source", "local_storage")
                            .put("track", title)
                            .put("artist", artist)
                            .put("uri", contentUri.toString())
                            .put("is_music_active", true)
                            .put("note", "Local track '$title' by '$artist' started playing in device audio player.")
                            .toString()
                    }
                }
            }
        }.onFailure { e ->
            AppDebugServer.log("ACTION", "Local MediaStore search failed: ${e.message}")
        }

        // 3. If NOT found in local library, DO NOT pretend it played!
        // Launch YouTube streaming search in browser so the song actually plays.
        val ytUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")
        val intent = Intent(Intent.ACTION_VIEW, ytUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            AppDebugServer.log("ACTION", "Opened YouTube search in browser for: $q")
            return JSONObject()
                .put("tool", "PLAY_MUSIC")
                .put("status", "not_found_locally")
                .put("action_taken", "opened_in_browser")
                .put("query", q)
                .put("streaming_url", ytUri.toString())
                .put("is_music_active", am?.isMusicActive == true)
                .put("note", "Track '$q' was not found in local files. Opened YouTube search in the browser so the user can play it. Tell the user you opened it on YouTube since it wasn't downloaded.")
                .toString()
        }

        return JSONObject()
            .put("tool", "PLAY_MUSIC")
            .put("status", "not_found")
            .put("query", q)
            .put("is_music_active", am?.isMusicActive == true)
            .put("note", "Could not find local track for \"$q\" and no browser available to stream it.")
            .toString()
    }

    /**
     * Searches directly inside specific applications (YouTube, Spotify, Browser, Maps, Play Store).
     */
    fun searchApp(app: String, query: String): String {
        val target = app.lowercase().trim()
        val q = query.trim()
        AppDebugServer.log("ACTION", "Executing searchApp: $target for '$q'")

        when (target) {
            "youtube" -> {
                val ytUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")
                val intent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                    `package` = "com.google.android.youtube"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return JSONObject().put("tool", "SEARCH_APP").put("app", "YouTube").put("query", q).put("success", true).toString()
                }
                val webIntent = Intent(Intent.ACTION_VIEW, ytUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (webIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(webIntent)
                    return JSONObject().put("tool", "SEARCH_APP").put("app", "YouTube (browser)").put("query", q).put("success", true).toString()
                }
            }
            "spotify" -> {
                val spotifyUri = Uri.parse("spotify:search:${Uri.encode(q)}")
                val intent = Intent(Intent.ACTION_VIEW, spotifyUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return JSONObject().put("tool", "SEARCH_APP").put("app", "Spotify").put("query", q).put("success", true).toString()
                }
            }
            "browser", "chrome", "brave", "web" -> {
                val searchUri = Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")
                val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return JSONObject().put("tool", "SEARCH_APP").put("app", "Browser").put("query", q).put("success", true).toString()
                }
            }
            "maps", "google maps" -> {
                val mapUri = Uri.parse("geo:0,0?q=${Uri.encode(q)}")
                val intent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return JSONObject().put("tool", "SEARCH_APP").put("app", "Maps").put("query", q).put("success", true).toString()
                }
            }
            "playstore", "play store" -> {
                val playUri = Uri.parse("market://search?q=${Uri.encode(q)}")
                val intent = Intent(Intent.ACTION_VIEW, playUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return JSONObject().put("tool", "SEARCH_APP").put("app", "Play Store").put("query", q).put("success", true).toString()
                }
            }
        }
        return JSONObject().put("tool", "SEARCH_APP").put("app", app).put("query", q).put("success", false).put("message", "could not search in $app").toString()
    }

    /**
     * Lists installed applications on the device, optionally filtered by category (music, browser, media, all).
     */
    fun listApps(filter: String = ""): String {
        val f = filter.lowercase().trim()
        AppDebugServer.log("ACTION", "Executing listApps: filter='$f'")
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val matches = mutableListOf<String>()

        for (pkg in packages) {
            val label = pm.getApplicationLabel(pkg).toString()
            val pName = pkg.packageName
            val isSystem = (pkg.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

            when (f) {
                "music", "audio", "media" -> {
                    if (pName.contains("music") || pName.contains("audio") || pName.contains("player") ||
                        pName.contains("spotify") || pName.contains("youtube") || pName.contains("mediacenter") ||
                        label.contains("music", ignoreCase = true) || label.contains("player", ignoreCase = true)
                    ) {
                        matches.add("$label ($pName)")
                    }
                }
                "browser" -> {
                    if (pName.contains("browser") || pName.contains("chrome") || label.contains("browser", ignoreCase = true)) {
                        matches.add("$label ($pName)")
                    }
                }
                else -> {
                    if (!isSystem || pName.contains("chrome") || pName.contains("mediacenter") || pName.contains("camera")) {
                        if (f.isEmpty() || label.contains(f, ignoreCase = true) || pName.contains(f)) {
                            matches.add("$label ($pName)")
                        }
                    }
                }
            }
        }
        val array = JSONArray()
        matches.take(20).forEach { array.put(it) }
        return JSONObject()
            .put("tool", "LIST_APPS")
            .put("filter", f)
            .put("count", matches.size)
            .put("apps", array)
            .toString()
    }

    /**
     * Returns detailed live device status (volume levels, ringer mode, active playback state).
     */
    fun getDeviceState(): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mediaVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val maxMediaVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        val ringerMode = when (am?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
        val isMusicActive = am?.isMusicActive ?: false
        val currentTrack = MusicTracker.currentTrack
        val trackDesc = if (currentTrack != null && isMusicActive) {
            "\"${currentTrack.title}\" by ${currentTrack.artist} (${currentTrack.app})"
        } else if (isMusicActive) "active (metadata loading)" else "none"

        return JSONObject()
            .put("tool", "GET_DEVICE_STATE")
            .put("media_volume", "$mediaVol/$maxMediaVol")
            .put("ringer_mode", ringerMode)
            .put("music", trackDesc)
            .put("is_music_active", isMusicActive)
            .toString()
    }

    /**
     * Dedicated audio & volume control.
     * Actions: restore, mute, unmute, volume_up, volume_down, set (with level: 0..100).
     */
    fun volumeControl(action: String, level: Int = -1): String {
        val act = action.lowercase().trim()
        AppDebugServer.log("ACTION", "Executing volumeControl: action='$act', level=$level")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return JSONObject().put("tool", "VOLUME_CONTROL").put("error", "audio service unavailable").toString()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val prev = am.getStreamVolume(AudioManager.STREAM_MUSIC)

        when {
            act in setOf("mute", "silence", "quiet") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            }
            act in setOf("unmute", "restore", "restore_volume", "restore volume", "put_back_volume", "put back volume", "un-mute") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                if (prev <= 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (max / 2).coerceAtLeast(1), AudioManager.FLAG_SHOW_UI)
                }
            }
            act in setOf("volume_up", "volume up", "louder", "raise", "increase", "up", "lakasan") || act.startsWith("volume_up") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            }
            act in setOf("volume_down", "volume down", "quieter", "lower", "decrease", "down", "hinaan") || act.startsWith("volume_down") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
            act.startsWith("set") || level >= 0 || act.startsWith("volume_") -> {
                val targetPct = if (level >= 0) level else act.filter { it.isDigit() }.toIntOrNull() ?: 50
                val target = (targetPct * max / 100).coerceIn(0, max)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            }
        }

        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return JSONObject()
            .put("tool", "VOLUME_CONTROL")
            .put("action", act)
            .put("previous_volume", prev)
            .put("current_volume", cur)
            .put("max_volume", max)
            .put("is_muted", cur == 0)
            .put("success", true)
            .toString()
    }

    /** Legacy alias for volumeControl. */
    fun deviceAction(action: String): String = volumeControl(action)

    /**
     * Finds and opens an installed application matching query by label or package name.
     */
    fun openApp(query: String): String {
        val target = query.lowercase().trim()
        if (target.isBlank()) return JSONObject().put("tool", "OPEN_APP").put("success", false).put("message", "no app name specified").toString()
        AppDebugServer.log("ACTION", "Executing OPEN_APP: $target")

        // 0. Direct system action intents for well-known feature targets
        when (target) {
            "alarm", "alarms" -> {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return JSONObject().put("tool", "OPEN_APP").put("app", "Alarm Clock").put("success", true).toString()
                }
            }
            "timer", "timers" -> {
                val intent = Intent(AlarmClock.ACTION_SHOW_TIMERS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return JSONObject().put("tool", "OPEN_APP").put("app", "Timer").put("success", true).toString()
                }
            }
        }

        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        // 1. Direct package match
        val directPkg = packages.firstOrNull { it.packageName.equals(target, ignoreCase = true) }
        if (directPkg != null) {
            val intent = pm.getLaunchIntentForPackage(directPkg.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return JSONObject().put("tool", "OPEN_APP").put("app", pm.getApplicationLabel(directPkg).toString()).put("package", directPkg.packageName).put("success", true).toString()
            }
        }

        // 2. Match label (e.g. "spotify", "youtube", "chrome")
        val labelMatch = packages.firstOrNull {
            val label = pm.getApplicationLabel(it).toString().lowercase()
            label == target || label.contains(target) || target.contains(label)
        }
        if (labelMatch != null) {
            val intent = pm.getLaunchIntentForPackage(labelMatch.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return JSONObject().put("tool", "OPEN_APP").put("app", pm.getApplicationLabel(labelMatch).toString()).put("package", labelMatch.packageName).put("success", true).toString()
            }
        }

        // 3. System alias mapping for common apps
        val aliasKeywords = when (target) {
            "clock", "alarm", "alarms", "timer", "stopwatch" -> listOf("deskclock", "clock")
            "calculator", "calc" -> listOf("calculator", "calc")
            "gallery", "photos" -> listOf("gallery", "photos")
            "camera" -> listOf("camera")
            "messages", "sms" -> listOf("mms", "messaging", "message")
            "phone", "dialer", "call" -> listOf("dialer", "phone")
            "contacts" -> listOf("contacts")
            "browser", "chrome", "internet" -> listOf("chrome", "browser")
            "files", "file manager" -> listOf("filemanager", "files", "documentsui")
            "notes", "notepad" -> listOf("notepad", "notes")
            "music" -> listOf("music", "spotify")
            else -> emptyList()
        }
        if (aliasKeywords.isNotEmpty()) {
            val aliasPkg = packages.firstOrNull { pkg ->
                val pName = pkg.packageName.lowercase()
                val lbl = pm.getApplicationLabel(pkg).toString().lowercase()
                aliasKeywords.any { kw -> pName.contains(kw) || lbl.contains(kw) }
            }
            if (aliasPkg != null) {
                val intent = pm.getLaunchIntentForPackage(aliasPkg.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return JSONObject().put("tool", "OPEN_APP").put("app", pm.getApplicationLabel(aliasPkg).toString()).put("package", aliasPkg.packageName).put("success", true).toString()
                }
            }
        }

        // 4. Fallback partial package match (e.g. "com.spotify.music" matches "spotify")
        val pkgMatch = packages.firstOrNull { it.packageName.lowercase().contains(target) }
        if (pkgMatch != null) {
            val intent = pm.getLaunchIntentForPackage(pkgMatch.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return JSONObject().put("tool", "OPEN_APP").put("app", pm.getApplicationLabel(pkgMatch).toString()).put("package", pkgMatch.packageName).put("success", true).toString()
            }
        }

        // 5. Build an array of up to 15 installed app names to feed back so Shiina knows what is available
        val availableNames = packages.mapNotNull { 
            val label = pm.getApplicationLabel(it).toString()
            if ((it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 && pm.getLaunchIntentForPackage(it.packageName) != null) label else null
        }.take(15)

        return JSONObject()
            .put("tool", "OPEN_APP")
            .put("app", target)
            .put("success", false)
            .put("message", "Could not find app matching \"$target\". (Note: try using LIST_APPS tool first if unsure of the name. Available non-system apps include: ${availableNames.joinToString(", ")})")
            .toString()
    }

    /**
     * Dumps a human-readable and model-friendly summary of the active screen elements,
     * including display dimensions, interactive element IDs, labels, types, and coordinates.
     */
    fun getScreenElementsSummary(): String {
        val service = ShiinaAccessibilityService.instance ?: return "Accessibility service not active"
        val elements = service.dumpInteractiveElements()
        if (elements.isEmpty()) return "No interactive elements detected on screen"
        val dm = context.resources.displayMetrics
        val sb = StringBuilder()
        sb.append("Display Resolution: ${dm.widthPixels}x${dm.heightPixels}\n")
        sb.append("Interactive Elements (${elements.size}):\n")
        elements.take(35).forEach { elem ->
            val label = when {
                elem.isEditable && elem.text.isNotBlank() -> "[Input Field: \"${elem.text}\"]"
                elem.isEditable -> "[Editable Input Field]"
                elem.text.isNotBlank() && elem.desc.isNotBlank() && elem.text != elem.desc -> "\"${elem.text}\" (${elem.desc})"
                elem.text.isNotBlank() -> "\"${elem.text}\""
                elem.desc.isNotBlank() -> "[desc: \"${elem.desc}\"]"
                else -> "[${elem.type}]"
            }
            val flags = mutableListOf<String>()
            if (elem.isClickable) flags.add("clickable")
            if (elem.isEditable) flags.add("editable")
            val flagStr = if (flags.isNotEmpty()) " (${flags.joinToString(",")})" else ""
            sb.append("- [${elem.id}] $label at (${elem.centerX}, ${elem.centerY})$flagStr\n")
        }
        return sb.toString().trim()
    }

    /**
     * Taps the screen targeting by element ID, text matching, or coordinates.
     * Grounding priority:
     * 1. elementId: Clicks exact cached node or taps exact center coordinates from screen hierarchy.
     * 2. text: Matches text in accessibility node hierarchy with physical tap fallback.
     * 3. coordinates (x, y): Normalized (0..1000) or physical screen pixels.
     */
    suspend fun tapScreen(x: Float, y: Float, text: String = "", elementId: Int = -1, isLongPress: Boolean = false): String {
        AppDebugServer.log("ACTION", "Executing TAP_SCREEN: elementId=$elementId, text='$text', x=$x, y=$y, isLongPress=$isLongPress")
        val service = ShiinaAccessibilityService.instance
        val metrics = screenMetrics ?: com.shiina.mobile.observation.ScreenMetrics(context)

        // 1. Target by elementId if explicitly provided
        if (service != null && elementId > 0) {
            val clicked = service.clickElementById(elementId)
            if (clicked) {
                return JSONObject()
                    .put("tool", "TAP_SCREEN")
                    .put("success", true)
                    .put("method", "accessibility_element_id")
                    .put("element_id", elementId)
                    .put("screen_resolution", metrics.toString())
                    .toString()
            }
        }

        // 2. Target by coordinates (x, y) if provided — visual ground truth
        if (x >= 0f && y >= 0f) {
            val (targetX, targetY) = metrics.toPixels(x, y)
            if (service != null) {
                val tapped = if (isLongPress) service.longPress(targetX, targetY) else service.tap(targetX, targetY)
                if (tapped) {
                    return JSONObject()
                        .put("tool", "TAP_SCREEN")
                        .put("success", true)
                        .put("method", if (isLongPress) "accessibility_long_press" else "accessibility_gesture")
                        .put("target_x", targetX)
                        .put("target_y", targetY)
                        .put("raw_x", x)
                        .put("raw_y", y)
                        .put("screen_resolution", metrics.toString())
                        .toString()
                }
            }

            // Fallback: attempt shell command if running with shell/ADB privileges
            val shellOk = runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input tap ${targetX.toInt()} ${targetY.toInt()}"))
                p.waitFor() == 0
            }.getOrDefault(false)
            if (shellOk) {
                return JSONObject()
                    .put("tool", "TAP_SCREEN")
                    .put("success", true)
                    .put("method", "shell")
                    .put("target_x", targetX)
                    .put("target_y", targetY)
                    .put("screen_resolution", metrics.toString())
                    .toString()
            }
        }

        // 3. Target by text if coordinates were not provided or gesture failed
        if (service != null && text.isNotBlank()) {
            val clickedByText = service.clickText(text)
            if (clickedByText) {
                return JSONObject()
                    .put("tool", "TAP_SCREEN")
                    .put("success", true)
                    .put("method", "accessibility_text")
                    .put("text", text)
                    .put("screen_resolution", metrics.toString())
                    .toString()
            }
        }

        val errorMsg = if (service == null) {
            "Could not tap screen: Accessibility Service is not enabled. Please enable 'Shiina Mobile' in Settings -> Accessibility."
        } else {
            "Tap gesture did not register (window may be animating, loading, or transitioning). Do not restart the app; inspect the screen with TAKE_SCREENSHOT before retrying."
        }

        return JSONObject()
            .put("tool", "TAP_SCREEN")
            .put("success", false)
            .put("error", errorMsg)
            .put("screen_resolution", metrics.toString())
            .toString()
    }

    /**
     * Swipes the screen between coordinates or in a given direction.
     */
    suspend fun swipeScreen(startX: Float, startY: Float, endX: Float, endY: Float, direction: String = ""): String {
        AppDebugServer.log("ACTION", "Executing SWIPE_SCREEN: ($startX, $startY) -> ($endX, $endY), direction='$direction'")
        val metrics = screenMetrics ?: com.shiina.mobile.observation.ScreenMetrics(context)
        val screenWidth = metrics.width.toFloat()
        val screenHeight = metrics.height.toFloat()

        var (sX, sY) = if (startX >= 0f && startY >= 0f) metrics.toPixels(startX, startY) else -1f to -1f
        var (eX, eY) = if (endX >= 0f && endY >= 0f) metrics.toPixels(endX, endY) else -1f to -1f

        if (sX < 0 || sY < 0 || eX < 0 || eY < 0) {
            val cx = screenWidth / 2f
            val cy = screenHeight / 2f
            when (direction.lowercase().trim()) {
                "up" -> { sX = cx; sY = cy + 400f; eX = cx; eY = cy - 400f }
                "down" -> { sX = cx; sY = cy - 400f; eX = cx; eY = cy + 400f }
                "left" -> { sX = cx + 300f; sY = cy; eX = cx - 300f; eY = cy }
                "right" -> { sX = cx - 300f; sY = cy; eX = cx + 300f; eY = cy }
                else -> { sX = cx; sY = cy + 400f; eX = cx; eY = cy - 400f }
            }
        }

        val service = ShiinaAccessibilityService.instance
        if (service != null) {
            val swiped = service.swipe(sX, sY, eX, eY)
            if (swiped) {
                return JSONObject()
                    .put("tool", "SWIPE_SCREEN")
                    .put("success", true)
                    .put("method", "accessibility_gesture")
                    .put("startX", sX)
                    .put("startY", sY)
                    .put("endX", eX)
                    .put("endY", eY)
                    .put("screen_resolution", metrics.toString())
                    .toString()
            }
        }

        val shellOk = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input swipe ${sX.toInt()} ${sY.toInt()} ${eX.toInt()} ${eY.toInt()} 300"))
            p.waitFor() == 0
        }.getOrDefault(false)
        if (shellOk) {
            return JSONObject()
                .put("tool", "SWIPE_SCREEN")
                .put("success", true)
                .put("method", "shell")
                .put("startX", sX)
                .put("startY", sY)
                .put("endX", eX)
                .put("endY", eY)
                .put("screen_resolution", metrics.toString())
                .toString()
        }

        return JSONObject()
            .put("tool", "SWIPE_SCREEN")
            .put("success", false)
            .put("error", "Accessibility Service is not enabled. Please enable 'Shiina Mobile' in Settings -> Accessibility to allow swipe gestures.")
            .toString()
    }

    /**
     * Types text into the currently focused input field.
     */
    fun inputText(text: String): String {
        AppDebugServer.log("ACTION", "Executing INPUT_TEXT: '$text'")
        val service = ShiinaAccessibilityService.instance
        if (service != null) {
            val ok = service.inputText(text)
            if (ok) {
                return JSONObject().put("tool", "INPUT_TEXT").put("success", true).put("text", text).toString()
            }
        }

        val escaped = text.replace(" ", "%s").replace("\"", "\\\"")
        val shellOk = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input text \"$escaped\""))
            p.waitFor() == 0
        }.getOrDefault(false)
        if (shellOk) {
            return JSONObject().put("tool", "INPUT_TEXT").put("success", true).put("method", "shell").put("text", text).toString()
        }

        return JSONObject()
            .put("tool", "INPUT_TEXT")
            .put("success", false)
            .put("error", "Could not input text. Enable 'Shiina Mobile' in Settings -> Accessibility or ensure an input field is focused.")
            .toString()
    }

    /**
     * Clears text from the currently focused input field.
     */
    fun clearText(): String {
        AppDebugServer.log("ACTION", "Executing CLEAR_TEXT")
        val service = ShiinaAccessibilityService.instance
        if (service != null && service.clearInputText()) {
            return JSONObject().put("tool", "CLEAR_TEXT").put("success", true).toString()
        }
        val shellOk = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent --longpress 67"))
            p.waitFor() == 0
        }.getOrDefault(false)
        return JSONObject().put("tool", "CLEAR_TEXT").put("success", shellOk).toString()
    }

    /**
     * Performs a global key/navigation action (back, home, recents, notifications, quick_settings, enter, search, volume).
     */
    fun pressKey(action: String): String {
        AppDebugServer.log("ACTION", "Executing PRESS_KEY: '$action'")
        val act = action.lowercase().trim()
        val service = ShiinaAccessibilityService.instance
        if (service != null) {
            val ok = service.pressGlobal(act)
            if (ok) {
                return JSONObject().put("tool", "PRESS_KEY").put("action", action).put("success", true).toString()
            }
        }

        val keycode = when (act) {
            "back", "dismiss_keyboard", "hide_keyboard" -> 4
            "home" -> 3
            "recents", "app_switch" -> 187
            "notifications", "notification_shade" -> 83
            "enter", "search" -> 66
            "volume_up", "vol_up" -> 24
            "volume_down", "vol_down" -> 25
            "volume_mute", "mute" -> 164
            "lock_screen", "power", "lock" -> 26
            else -> 4
        }
        val shellOk = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keycode"))
            p.waitFor() == 0
        }.getOrDefault(false)
        if (shellOk) {
            return JSONObject().put("tool", "PRESS_KEY").put("action", action).put("success", true).put("method", "shell").toString()
        }

        return JSONObject().put("tool", "PRESS_KEY").put("action", action).put("success", false).put("error", "Could not press key '$action'. Enable Accessibility Service.").toString()
    }

    /**
     * Toggles the device camera flashlight / torch on or off.
     */
    fun toggleFlashlight(action: String = ""): String {
        return runCatching {
            val cm = context.getSystemService(CameraManager::class.java)
                ?: return "Camera service unavailable"
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No camera flash found on device"

            val target = when (action.lowercase().trim()) {
                "off", "false", "disable" -> false
                else -> true
            }
            cm.setTorchMode(cameraId, target)
            "flashlight turned ${if (target) "on" else "off"}"
        }.getOrElse { "flashlight toggle failed: ${it.message}" }
    }

    /**
     * Launches Android system settings panels directly.
     */
    fun openSettings(panel: String = ""): String {
        return runCatching {
            val intentAction = when (panel.lowercase().trim()) {
                "wifi", "network", "internet" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth", "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "display", "screen" -> Settings.ACTION_DISPLAY_SETTINGS
                "sound", "volume", "audio" -> Settings.ACTION_SOUND_SETTINGS
                "battery", "power" -> Intent.ACTION_POWER_USAGE_SUMMARY
                "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
                "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                "date", "time" -> Settings.ACTION_DATE_SETTINGS
                "privacy", "security" -> Settings.ACTION_PRIVACY_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            val intent = Intent(intentAction).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "opened settings (${panel.ifBlank { "main" }})"
        }.getOrElse { "failed to open settings: ${it.message}" }
    }

    /**
     * Opens a web URL directly in the user's default web browser.
     */
    fun openUrl(url: String): String {
        return runCatching {
            val trimmed = url.trim()
            val targetUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                "https://$trimmed"
            } else trimmed
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "opened url in browser: $targetUrl"
        }.getOrElse { "failed to open url: ${it.message}" }
    }

    /**
     * Reads or copies text to the Android system clipboard.
     */
    fun manageClipboard(action: String, text: String = ""): String {
        return runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
                ?: return "Clipboard service unavailable"
            when (action.lowercase().trim()) {
                "copy", "set", "write" -> {
                    val clip = ClipData.newPlainText("Shiina", text)
                    cm.setPrimaryClip(clip)
                    "copied to clipboard: \"${text.take(60)}\""
                }
                "read", "get", "paste" -> {
                    val item = cm.primaryClip?.getItemAt(0)
                    val content = item?.text?.toString().orEmpty()
                    if (content.isNotBlank()) "clipboard contains: \"${content.take(200)}\""
                    else "clipboard is empty"
                }
                else -> "clipboard action must be 'copy' or 'read'"
            }
        }.getOrElse { "clipboard failed: ${it.message}" }
    }

    /**
     * Dispatches common system intents: dialer, maps, camera, clock, share.
     */
    fun systemIntent(action: String, data: String = ""): String {
        return runCatching {
            val intent = when (action.lowercase().trim()) {
                "dial", "call", "phone" -> {
                    val num = data.trim().filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))
                }
                "maps", "navigate", "location" -> {
                    val q = Uri.encode(data.trim())
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
                }
                "camera", "photo" -> {
                    Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                }
                "clock", "alarm" -> {
                    Intent(AlarmClock.ACTION_SHOW_ALARMS)
                }
                "share" -> {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, data.trim())
                    }
                }
                else -> return "unknown system intent: $action (supported: dial, maps, camera, clock, share)"
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "launched system action: $action"
        }.getOrElse { "system action '$action' failed: ${it.message}" }
    }

    /**
     * Detects and returns the active foreground application.
     */
    fun getForegroundApp(): String {
        val a11yPkg = ShiinaAccessibilityService.instance?.getCurrentPackageName()
        val pkg = if (!a11yPkg.isNullOrBlank()) a11yPkg else {
            val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
            val end = System.currentTimeMillis()
            val stats = usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, end - 60_000, end)
            stats?.filter { it.lastTimeUsed > end - 60_000 }?.maxByOrNull { it.lastTimeUsed }?.packageName
        }
        val pm = context.packageManager
        val appName = if (pkg != null) {
            runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
            }.getOrDefault(pkg)
        } else "Unknown"

        return JSONObject()
            .put("tool", "GET_FOREGROUND_APP")
            .put("success", pkg != null)
            .put("package_name", pkg ?: "unknown")
            .put("app_name", appName)
            .toString()
    }

    /**
     * Reads all visible text content from the current screen hierarchy.
     */
    fun readScreenText(): String {
        val service = ShiinaAccessibilityService.instance
        if (service == null) {
            return JSONObject()
                .put("tool", "READ_SCREEN_TEXT")
                .put("success", false)
                .put("error", "Accessibility service not active")
                .toString()
        }
        val text = service.getVisibleScreenText()
        return JSONObject()
            .put("tool", "READ_SCREEN_TEXT")
            .put("success", text.isNotBlank())
            .put("content", text.ifBlank { "No text detected on screen" })
            .put("chars", text.length)
            .toString()
    }

    /**
     * Sets the system ringer mode (normal, vibrate, silent).
     */
    fun setRingerMode(mode: String): String {
        return runCatching {
            val am = context.getSystemService(AudioManager::class.java)
                ?: return "Audio service unavailable"
            val target = when (mode.lowercase().trim()) {
                "silent" -> AudioManager.RINGER_MODE_SILENT
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            am.ringerMode = target
            "ringer mode set to $mode"
        }.getOrElse { "set ringer mode failed: ${it.message}" }
    }

    /**
     * Vibrates the physical device for haptic alerts.
     */
    fun vibrateDevice(durationMs: Long = 200): String {
        return runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(android.os.VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (v == null || !v.hasVibrator()) return "Device does not have vibrator"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
            "vibrated device for ${durationMs}ms"
        }.getOrElse { "vibrate failed: ${it.message}" }
    }

    /**
     * Returns rich metadata about the currently playing audio or media stream.
     */
    fun getCurrentPlaying(): String {
        val track = MusicTracker(context).getExactMusic()
        val isPlaying = track?.isPlaying ?: (context.getSystemService(AudioManager::class.java)?.isMusicActive == true)
        return JSONObject()
            .put("tool", "GET_CURRENT_PLAYING")
            .put("is_playing", isPlaying)
            .put("title", track?.title ?: "none")
            .put("artist", track?.artist ?: "none")
            .put("album", track?.album ?: "")
            .put("app", track?.app ?: "")
            .toString()
    }

    /**
     * Sets an Android alarm clock via standard system intent.
     */
    fun setAlarm(hour: Int, minute: Int, message: String = ""): String {
        return runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "alarm set for %02d:%02d%s".format(hour, minute, if (message.isNotBlank()) " ($message)" else "")
        }.getOrElse { err ->
            runCatching {
                val showIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(showIntent)
                "opened alarms screen (fallback): ${err.message}"
            }.getOrElse { fallbackErr -> "failed to set alarm: ${err.message}; fallback: ${fallbackErr.message}" }
        }
    }

    /**
     * Sets an Android countdown timer via standard system intent.
     */
    fun setTimer(seconds: Int, message: String = ""): String {
        return runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "timer set for ${seconds}s%s".format(if (message.isNotBlank()) " ($message)" else "")
        }.getOrElse { err ->
            runCatching {
                val showIntent = Intent(AlarmClock.ACTION_SHOW_TIMERS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(showIntent)
                "opened timers screen (fallback): ${err.message}"
            }.getOrElse { fallbackErr -> "failed to set timer: ${err.message}; fallback: ${fallbackErr.message}" }
        }
    }

    /**
     * Sends a local status or alert notification to the Android notification drawer.
     */
    fun sendNotification(title: String, message: String): String {
        return runCatching {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
                ?: return "Notification service unavailable"
            val channelId = "shiina_assistant_alerts"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId, "Shiina Alerts", android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }
            val notif = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title.ifBlank { "Shiina" })
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % 100000).toInt(), notif)
            "notification posted: $title"
        }.getOrElse { "failed to send notification: ${it.message}" }
    }

    /**
     * Closes the active foreground application by navigating home.
     */
    fun closeApp(): String {
        return pressKey("home")
    }
}
