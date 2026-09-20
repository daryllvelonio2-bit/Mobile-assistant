package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Track C4 — per-tool attempts/successes. NightlyReflection reads weekly rates. */
@Entity(tableName = "tool_stats")
data class ToolStat(
    @PrimaryKey val tool: String,
    val attempts: Int,
    val successes: Int,
    val updatedMillis: Long,
)

@Dao
interface ToolStatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stat: ToolStat)

    @Query("SELECT * FROM tool_stats WHERE tool = :tool")
    suspend fun get(tool: String): ToolStat?

    @Query("SELECT * FROM tool_stats")
    suspend fun all(): List<ToolStat>
}