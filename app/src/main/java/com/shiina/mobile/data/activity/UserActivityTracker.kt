package com.shiina.mobile.data.activity

import android.content.Context
import java.util.Calendar

/**
 * Tracks user activity and app usage timestamps.
 * Used to detect prolonged user absence (e.g. 10+ hours of inactivity)
 * to trigger Shiina's pouty mood.
 * MEM-10: also keeps a 14-day hour-of-day activity histogram so the
 * nightly reflection can time nudges to the user's real rhythm instead
 * of acted-interrupt counts alone.
 */
class UserActivityTracker(context: Context) {
    private val prefs = context.getSharedPreferences("user_activity", Context.MODE_PRIVATE)

    fun recordActivity() {
        val now = System.currentTimeMillis()
        val currentLast = prefs.getLong(KEY_LAST_ACTIVE, 0L)
        // If currentLast is older than 5 seconds, preserve it as previous active timestamp
        if (currentLast > 0L && (now - currentLast) > 5_000L) {
            prefs.edit().putLong(KEY_PREV_ACTIVE, currentLast).apply()
        }
        prefs.edit().putLong(KEY_LAST_ACTIVE, now).apply()
        runCatching {
            val day = (now / 86_400_000L).toInt()
            val hour = Calendar.getInstance().apply { timeInMillis = now }
                .get(Calendar.HOUR_OF_DAY)
            val set = prefs.getStringSet(KEY_HOURS, emptySet())!!.toMutableSet()
            set.add("$day:$hour")
            val cutoff = day - 14
            set.removeAll { (it.substringBefore(":").toIntOrNull() ?: 0) < cutoff }
            prefs.edit().putStringSet(KEY_HOURS, set).apply()
        }
    }

    /** MEM-10: hour-of-day -> active-hit count over the last 14 days. */
    fun activeHourHistogram(): Map<Int, Int> = runCatching {
        val set = prefs.getStringSet(KEY_HOURS, emptySet()).orEmpty()
        set.mapNotNull { it.substringAfter(":").toIntOrNull() }
            .groupingBy { it }.eachCount()
    }.getOrDefault(emptyMap())

    fun getLastActiveMillis(fallback: Long = 0L): Long {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_ACTIVE, 0L)
        // If last active was recorded just milliseconds ago (as part of handling the current turn),
        // use the previous active timestamp so the duration represents the user's actual absence!
        if (last > 0L && (now - last) < 5_000L) {
            val prev = prefs.getLong(KEY_PREV_ACTIVE, 0L)
            if (prev > 0L) return prev
        }
        return if (last > 0L) last else fallback
    }

    fun getHoursSinceLastActive(fallback: Long = 0L): Double {
        val last = getLastActiveMillis(fallback)
        if (last <= 0L) return 0.0
        val diff = System.currentTimeMillis() - last
        return (diff / (1000.0 * 3600.0)).coerceAtLeast(0.0)
    }

    fun isInactiveForHours(hours: Double = POUTY_THRESHOLD_HOURS, fallback: Long = 0L): Boolean {
        return getHoursSinceLastActive(fallback) >= hours
    }

    companion object {
        private const val KEY_LAST_ACTIVE = "last_user_active_millis"
        private const val KEY_PREV_ACTIVE = "prev_user_active_millis"
        private const val KEY_HOURS = "active_hour_histogram"
        const val POUTY_THRESHOLD_HOURS = 10.0
    }
}