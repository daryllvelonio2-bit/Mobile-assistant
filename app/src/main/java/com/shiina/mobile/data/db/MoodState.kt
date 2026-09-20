package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Dynamic Mood Balancing step 1 — persistent valence-arousal emotional state.
 * Single row (id=1): a continuous coordinate instead of discrete labels, so
 * mood can blend, decay, and survive process death.
 */
@Entity(tableName = "mood_state")
data class MoodState(
    @PrimaryKey val id: Int = 1,
    val valence: Double,
    val arousal: Double,
    val mood: String,
    val intensity: Double,
    val reason: String,
    val updatedMillis: Long,
)

@Dao
interface MoodStateDao {
    @Query("SELECT * FROM mood_state WHERE id = 1")
    suspend fun get(): MoodState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: MoodState)
}