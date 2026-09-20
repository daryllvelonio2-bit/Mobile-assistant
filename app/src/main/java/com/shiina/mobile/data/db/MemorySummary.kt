package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Weekly digest rows: the durable output of auto-compaction. Old episodes
 * are distilled into one digest per 7-day window BEFORE their raw rows are
 * deleted — compaction distills signal, it never just drops it.
 */
@Entity(tableName = "memory_summaries")
data class MemorySummary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val periodStartMillis: Long,
    val periodEndMillis: Long,
    val digest: String,
    val episodeCount: Int,
    val actedCount: Int,
    val dismissedCount: Int,
    val createdMillis: Long,
)

@Dao
interface MemorySummaryDao {
    @Insert
    suspend fun insert(summary: MemorySummary): Long

    @Query("SELECT * FROM memory_summaries ORDER BY periodEndMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemorySummary>

    @Query("SELECT COUNT(*) FROM memory_summaries")
    suspend fun count(): Int

    @Query("DELETE FROM memory_summaries")
    suspend fun deleteAll()
}
