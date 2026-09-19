package com.shiina.mobile.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [UsageEvent::class, SleepRecord::class, GoalEntry::class, BaselineSnapshot::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    abstract fun sleepDao(): SleepDao
    abstract fun goalDao(): GoalDao
    abstract fun baselineDao(): BaselineDao
}
