package com.shiina.mobile.data.activity

import android.content.Context

/**
 * Tracks user activity and app usage timestamps.
 * Used to detect prolonged user absence (e.g. 10+ hours of inactivity)
 * to trigger Shiina's pouty mood.
 */
class UserActivityTracker(context: Context) {
    private val prefs = context.getSharedPreferences("user_activity", Context.MODE_PRIVATE)

    fun recordActivity() {
        prefs.edit().putLong(KEY_LAST_ACTIVE, System.currentTimeMillis()).apply()
    }

    fun getLastActiveMillis(fallback: Long = 0L): Long {
        val saved = prefs.getLong(KEY_LAST_ACTIVE, 0L)
        return if (saved > 0L) saved else fallback
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
        const val POUTY_THRESHOLD_HOURS = 10.0
    }
}
