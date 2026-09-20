package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Audit A3 — persisted reminders. Alarms alone don't survive reboot;
 * the row does. BOOT_COMPLETED re-schedules every unfired row, and the
 * receiver deletes the row when it fires.
 */
@Entity(tableName = "reminders")
data class ReminderEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val fireMillis: Long,
    val createdMillis: Long,
)

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: ReminderEntry): Long

    @Query("SELECT * FROM reminders WHERE fireMillis > :now ORDER BY fireMillis ASC")
    suspend fun pendingAfter(now: Long): List<ReminderEntry>

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE fireMillis <= :now")
    suspend fun deleteFired(now: Long)
}