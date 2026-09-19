package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(event: UsageEvent)

    @Query("SELECT * FROM usage_events WHERE dayStartMillis = :day ORDER BY id DESC")
    suspend fun forDay(day: Long): List<UsageEvent>
}
