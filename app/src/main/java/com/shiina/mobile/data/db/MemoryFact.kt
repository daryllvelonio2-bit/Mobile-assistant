package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One semantic fact about the user (Phase 3). Confidence 0..1: stated
 * facts start at 1.0, inferred at 0.5, +0.1 per corroboration. Capped at
 * ~200 rows — lowest confidence evicted first. On-device only.
 */
@Entity(tableName = "memory_facts")
data class MemoryFact(
    @PrimaryKey val key: String,
    val value: String,
    val confidence: Double,
    val source: String,
    val updatedMillis: Long,
)

@Dao
interface MemoryFactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: MemoryFact)

    @Query("SELECT * FROM memory_facts ORDER BY confidence DESC, updatedMillis DESC LIMIT :limit")
    suspend fun topByConfidence(limit: Int): List<MemoryFact>

    @Query("SELECT * FROM memory_facts ORDER BY `key` ASC")
    suspend fun all(): List<MemoryFact>

    @Query("SELECT * FROM memory_facts WHERE `key` = :key")
    suspend fun get(key: String): MemoryFact?

    @Query("DELETE FROM memory_facts WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM memory_facts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM memory_facts")
    suspend fun count(): Int

    @Query(
        "DELETE FROM memory_facts WHERE `key` IN " +
            "(SELECT `key` FROM memory_facts ORDER BY confidence ASC, updatedMillis ASC LIMIT :excess)",
    )
    suspend fun evictLowest(excess: Int)

    @Query("DELETE FROM memory_facts WHERE confidence < :minConfidence AND updatedMillis < :beforeMillis")
    suspend fun pruneWeak(minConfidence: Double, beforeMillis: Long)
}