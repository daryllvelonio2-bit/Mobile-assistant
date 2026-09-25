package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * User-set behavior triggers ("scold me when it's 12"). Unlike reminders,
 * a trigger fires a LOCKED overlay that cannot be dismissed until the user
 * replies to her — she holds them to it. strict=true (scold/sleep words)
 * uses firm tone. Persisted so reboot restores them.
 */
@Entity(tableName = "triggers")
data class TriggerEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val fireMillis: Long,
    val createdMillis: Long,
    val repeatType: String = "none",
    val strict: Boolean = false,
)

@Dao
interface TriggerDao {
    @Insert
    suspend fun insert(trigger: TriggerEntry): Long

    @Update
    suspend fun update(trigger: TriggerEntry)

    @Query("SELECT * FROM triggers WHERE id = :id")
    suspend fun get(id: Long): TriggerEntry?

    @Query("SELECT * FROM triggers WHERE fireMillis > :now ORDER BY fireMillis ASC")
    suspend fun pendingAfter(now: Long): List<TriggerEntry>

    @Query("DELETE FROM triggers WHERE id = :id")
    suspend fun delete(id: Long)
}