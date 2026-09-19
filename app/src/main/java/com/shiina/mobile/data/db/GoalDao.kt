package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface GoalDao {
    @Insert
    suspend fun insert(goal: GoalEntry): Long

    @Update
    suspend fun update(goal: GoalEntry)

    @Query("SELECT * FROM goals ORDER BY updatedMillis DESC")
    suspend fun all(): List<GoalEntry>
}
