package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One logged decision round (episodic memory, Phase 1) plus what came of it
 * (Phase 2 outcomes). Telemetry snapshot in, decision out, then observed
 * reactions: shown (overlay rendered), dismissed (hidden within 60s),
 * acted (recommended action executed), talkedBack (Talk reply within 5 min).
 * The message is stored as a hash only — never full text.
 */
@Entity(tableName = "memory_episodes")
data class MemoryEpisode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val source: String,
    val entertainmentMinutes: Int,
    val entertainmentBaseline: Double,
    val goalsOpen: Int,
    val goalsDone: Int,
    val goalsMissed: Int,
    val tone: String,
    val interrupt: Boolean,
    val action: String,
    val messageHash: Int,
    val shown: Boolean = false,
    val dismissed: Boolean = false,
    val acted: Boolean = false,
    val talkedBack: Boolean = false,
    /** Track B3: what she actually did that round (action list results). */
    val actionSummary: String = "",
)

@Dao
interface MemoryEpisodeDao {
    @Insert
    suspend fun insert(episode: MemoryEpisode): Long

    @Query("SELECT * FROM memory_episodes ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryEpisode>

    @Query("SELECT COUNT(*) FROM memory_episodes")
    suspend fun count(): Int

    @Query("SELECT * FROM memory_episodes WHERE interrupt = 1 ORDER BY timestampMillis DESC LIMIT 1")
    suspend fun lastInterrupt(): MemoryEpisode?

    @Query("SELECT COUNT(*) FROM memory_episodes WHERE dismissed = 1 AND timestampMillis >= :sinceMillis")
    suspend fun dismissedCountSince(sinceMillis: Long): Int

    @Query("UPDATE memory_episodes SET shown = 1 WHERE id = :id")
    suspend fun markShown(id: Long)

    @Query("UPDATE memory_episodes SET dismissed = 1 WHERE id = :id")
    suspend fun markDismissed(id: Long)

    @Query("UPDATE memory_episodes SET acted = 1 WHERE id = :id")
    suspend fun markActed(id: Long)

    @Query("UPDATE memory_episodes SET talkedBack = 1 WHERE id = :id")
    suspend fun markTalkedBack(id: Long)

    @Query("UPDATE memory_episodes SET actionSummary = :summary WHERE id = :id")
    suspend fun updateActionSummary(id: Long, summary: String)

    @Query("SELECT * FROM memory_episodes WHERE timestampMillis >= :sinceMillis ORDER BY timestampMillis DESC")
    suspend fun episodesSince(sinceMillis: Long): List<MemoryEpisode>

    @Query("SELECT * FROM memory_episodes WHERE timestampMillis < :beforeMillis ORDER BY timestampMillis ASC LIMIT 1")
    suspend fun oldestBefore(beforeMillis: Long): MemoryEpisode?

    @Query("SELECT * FROM memory_episodes WHERE timestampMillis >= :startMillis AND timestampMillis < :endMillis ORDER BY timestampMillis ASC")
    suspend fun episodesInRange(startMillis: Long, endMillis: Long): List<MemoryEpisode>

    @Query("DELETE FROM memory_episodes WHERE timestampMillis < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long)

    @Query("DELETE FROM memory_episodes")
    suspend fun deleteAll()

    @Query("SELECT COUNT(DISTINCT (timestampMillis / 86400000)) FROM memory_episodes")
    suspend fun distinctDayCount(): Int
}