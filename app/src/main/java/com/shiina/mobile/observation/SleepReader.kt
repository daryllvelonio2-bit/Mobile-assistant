package com.shiina.mobile.observation

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

class SleepReader(private val context: Context) {

    suspend fun getRecentSleepDurationMinutes(): Long {
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val end = Instant.now()
            val start = end.minus(1, ChronoUnit.DAYS)
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            var totalMinutes = 0L
            for (record in response.records) {
                val duration = ChronoUnit.MINUTES.between(record.startTime, record.endTime)
                totalMinutes += duration
            }
            totalMinutes
        } catch (_: Exception) {
            480L // Fallback 8 hours if Health Connect not authorized or available
        }
    }
}
