package com.shiina.mobile.observation

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entertainment screen-time reader.
 * Audit C7: query is blocking — exposed as suspend on Dispatchers.IO so
 * callers never pin the main thread.
 */
class UsageReader(private val context: Context) {

    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)

    /** Known entertainment package heuristics or user categories. */
    private val entertainmentPackages = setOf(
        "com.google.android.youtube",
        "com.netflix.mediaclient",
        "com.zhiliaoapp.musically", // TikTok
        "com.instagram.android",
        "com.reddit.frontpage",
        "com.twitter.android",
        "com.discord"
    )

    suspend fun getTodayEntertainmentMinutes(): Int = withContext(Dispatchers.IO) {
        if (usageStatsManager == null) return@withContext 0
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, startTime, endTime
        ) ?: return@withContext 0

        var totalMillis = 0L
        for (stat in stats) {
            if (isEntertainment(stat.packageName)) {
                totalMillis += stat.totalTimeInForeground
            }
        }
        (totalMillis / (1000 * 60)).toInt()
    }

    private fun isEntertainment(pkg: String): Boolean {
        return entertainmentPackages.any { pkg.contains(it, ignoreCase = true) }
    }
}