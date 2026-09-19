package com.shiina.mobile.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Rolling 7-day baseline snapshot per dimension. */
@Entity(tableName = "baselines")
data class BaselineSnapshot(
    @PrimaryKey val dimension: String,
    val average: Double,
    val updatedMillis: Long,
)
