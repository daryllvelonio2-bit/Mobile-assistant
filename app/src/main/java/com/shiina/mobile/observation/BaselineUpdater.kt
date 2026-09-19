package com.shiina.mobile.observation

import com.shiina.mobile.data.db.BaselineDao
import com.shiina.mobile.data.db.BaselineSnapshot
import com.shiina.mobile.data.db.UsageDao

class BaselineUpdater(
    private val baselineDao: BaselineDao,
    private val usageDao: UsageDao,
) {

    suspend fun updateEntertainmentBaseline(todayMinutes: Int) {
        val existing = baselineDao.get("entertainment_minutes")
        val currentAvg = existing?.average ?: todayMinutes.toDouble()
        // Exponential moving average or 7-day smoothing: newAvg = oldAvg * 0.85 + today * 0.15
        val smoothed = (currentAvg * 0.85) + (todayMinutes * 0.15)
        baselineDao.upsert(
            BaselineSnapshot(
                dimension = "entertainment_minutes",
                average = smoothed,
                updatedMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun getEntertainmentBaseline(): Double {
        return baselineDao.get("entertainment_minutes")?.average ?: 120.0
    }
}
