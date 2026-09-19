package com.shiina.mobile.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Goal lifecycle: 0 = open, 1 = done, 2 = missed. */
@Entity(tableName = "goals")
data class GoalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val status: Int = 0,
    val createdMillis: Long,
    val updatedMillis: Long,
)
