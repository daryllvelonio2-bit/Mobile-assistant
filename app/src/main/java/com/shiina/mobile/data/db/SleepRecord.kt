package com.shiina.mobile.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One night of sleep timing, read from Health Connect or fallback input. */
@Entity(tableName = "sleep_records")
data class SleepRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bedMillis: Long,
    val wakeMillis: Long,
    val dayStartMillis: Long,
)
