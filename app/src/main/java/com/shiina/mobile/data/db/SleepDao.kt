package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SleepDao {
    @Insert
    suspend fun insert(record: SleepRecord)

    @Query("SELECT * FROM sleep_records ORDER BY wakeMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SleepRecord>
}
