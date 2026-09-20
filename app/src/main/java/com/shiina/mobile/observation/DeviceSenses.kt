package com.shiina.mobile.observation

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Track A1 — device senses: time/date, battery + charging + low flag, screen
 * state, ringer mode, foreground app, music, top apps today, hour bucket.
 * Everything the phone already knows, built as one JSON block for every
 * prompt so she never spends a tool call finding it. No new permissions
 * (usage stats + battery already wired).
 * Audit S5: snapshot cached 30s so repeated rounds don't re-query UsageStats.
 * Audit S7: foreground app degrades to an honest "unknown" instead of
 * silently disappearing when usage access is missing.
 */
class DeviceSenses(
    private val context: Context,
    private val musicTracker: MusicTracker? = null,
    private val screenMetrics: ScreenMetrics? = null,
) {

    @Volatile private var cached: String = ""
    @Volatile private var cachedAt: Long = 0L

    fun snapshot(): String {
        val now = System.currentTimeMillis()
        if (cached.isNotEmpty() && now - cachedAt < CACHE_MS) return cached
        return runCatching {
            val fmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault())
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val batt = battery()
            val track = musicTracker?.getExactMusic()
            val isPlaying = track?.isPlaying ?: musicPlaying()
            val metrics = screenMetrics ?: ScreenMetrics(context)
            val json = JSONObject()
                .put("time", fmt.format(Date()))
                .put("hour_bucket", bucket(hour))
                .put("battery", batt)
                .put("low_battery", batt in 0..15)
                .put("charging", charging())
                .put("screen", if (screenOn()) "on" else "off")
                .put("screen_resolution", metrics.toString())
                .put("ringer", ringer())
                .put("music_playing", isPlaying)
                .put("foreground_app", foregroundApp() ?: "unknown (no usage access)")
            val am = context.getSystemService(AudioManager::class.java)
            val curVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
            val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
            if (curVol >= 0 && maxVol > 0) {
                json.put("media_volume", "$curVol/$maxVol")
            }
            if (track != null && isPlaying) {
                val desc = if (track.artist.isNotBlank()) "${track.title} by ${track.artist}" else track.title
                json.put("current_music", desc.trim())
                if (track.album.isNotBlank()) json.put("music_album", track.album)
                if (track.app.isNotBlank()) json.put("music_app", track.app)
            } else {
                json.put("current_music", null)
            }
            val players = getAvailableMusicPlayers()
            if (players.isNotEmpty()) {
                json.put("available_music_players", JSONArray(players))
            }
            topApps()?.let { json.put("top_apps_today", it) }
            cached = json.toString()
            cachedAt = now
            cached
        }.getOrDefault("{}")
    }

    private fun getAvailableMusicPlayers(): List<String> = runCatching {
        val pm = context.packageManager
        val players = mutableListOf<String>()
        val common = listOf(
            "com.android.mediacenter" to "Huawei Music",
            "com.spotify.music" to "Spotify",
            "com.google.android.youtube" to "YouTube",
            "com.kyotoplayer" to "Kyoto Player",
            "com.brave.browser" to "Brave",
            "com.android.chrome" to "Chrome",
        )
        for ((p, name) in common) {
            if (runCatching { pm.getPackageInfo(p, 0) }.isSuccess) {
                players.add("$name ($p)")
            }
        }
        players
    }.getOrDefault(emptyList())

    fun isMusicPlaying(): Boolean = musicPlaying()

    private fun battery(): Int = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }.getOrDefault(-1)

    private fun charging(): Boolean = runCatching {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    private fun screenOn(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: false
    }.getOrDefault(false)

    private fun ringer(): String = runCatching {
        when (context.getSystemService(AudioManager::class.java)?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "normal"
        }
    }.getOrDefault("normal")

    private fun musicPlaying(): Boolean = runCatching {
        context.getSystemService(AudioManager::class.java)?.isMusicActive ?: false
    }.getOrDefault(false)

    /** Package with the most recent lastTimeUsed inside the last 60s. */
    private fun foregroundApp(): String? = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 60_000, end)
            ?: return null
        stats.filter { it.lastTimeUsed > end - 60_000 }
            .maxByOrNull { it.lastTimeUsed }?.packageName
    }.getOrNull()

    /** Audit S4: top-3 apps by foreground minutes today (self excluded). */
    private fun topApps(): JSONArray? = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis(),
        ) ?: return null
        val arr = JSONArray()
        stats.filter { it.packageName != context.packageName && it.totalTimeInForeground > 60_000 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(3)
            .forEach { arr.put("${it.packageName} ${it.totalTimeInForeground / 60_000}min") }
        if (arr.length() > 0) arr else null
    }.getOrNull()

    private fun bucket(hour: Int): String = when (hour) {
        in 22..23, in 0..4 -> "night"
        in 5..11 -> "morning"
        in 12..17 -> "afternoon"
        else -> "evening"
    }

    companion object {
        private const val CACHE_MS = 30_000L
    }
}