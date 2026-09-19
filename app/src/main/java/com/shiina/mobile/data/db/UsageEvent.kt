package com.shiina.mobile.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One observed app-use sample. Category comes from the local category map. */
@Entity(tableName = "usage_events")
data class UsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val category: String,
    val foregroundMillis: Long,
    val dayStartMillis: Long,
)
