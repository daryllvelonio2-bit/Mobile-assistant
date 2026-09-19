package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BaselineDao {
    @Upsert
    suspend fun upsert(snapshot: BaselineSnapshot)

    @Query("SELECT * FROM baselines WHERE dimension = :dimension")
    suspend fun get(dimension: String): BaselineSnapshot?
}
