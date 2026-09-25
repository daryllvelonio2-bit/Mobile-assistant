package com.shiina.mobile.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UsageEvent::class,
        SleepRecord::class,
        GoalEntry::class,
        BaselineSnapshot::class,
        MemoryEpisode::class,
        MemoryFact::class,
        ChatTurn::class,
        MemorySummary::class,
        ToolStat::class,
        ReminderEntry::class,
        MoodState::class,
        TriggerEntry::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    abstract fun sleepDao(): SleepDao
    abstract fun goalDao(): GoalDao
    abstract fun baselineDao(): BaselineDao
    abstract fun memoryEpisodeDao(): MemoryEpisodeDao
    abstract fun memoryFactDao(): MemoryFactDao
    abstract fun chatTurnDao(): ChatTurnDao
    abstract fun memorySummaryDao(): MemorySummaryDao
    abstract fun toolStatDao(): ToolStatDao
    abstract fun reminderDao(): ReminderDao
    abstract fun moodStateDao(): MoodStateDao
    abstract fun triggerDao(): TriggerDao
}

/** v2: episodic memory — one row per decision round. Never destructive. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_episodes` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestampMillis` INTEGER NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`entertainmentMinutes` INTEGER NOT NULL, " +
                "`entertainmentBaseline` REAL NOT NULL, " +
                "`goalsOpen` INTEGER NOT NULL, " +
                "`goalsDone` INTEGER NOT NULL, " +
                "`goalsMissed` INTEGER NOT NULL, " +
                "`tone` TEXT NOT NULL, " +
                "`interrupt` INTEGER NOT NULL, " +
                "`action` TEXT NOT NULL, " +
                "`messageHash` INTEGER NOT NULL)",
        )
    }
}

/** v3: episode outcomes — what the user did about each interrupt. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memory_episodes` ADD COLUMN `shown` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_episodes` ADD COLUMN `dismissed` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_episodes` ADD COLUMN `acted` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_episodes` ADD COLUMN `talkedBack` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v4: semantic facts — things she knows about the user. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_facts` (" +
                "`key` TEXT NOT NULL, " +
                "`value` TEXT NOT NULL, " +
                "`confidence` REAL NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`updatedMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`key`))",
        )
    }
}

/** v5: chat continuity — Talk turns kept for thread memory. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_turns` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`role` TEXT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`timestampMillis` INTEGER NOT NULL)",
        )
    }
}

/** v6: compaction digests — distilled weekly history, never raw loss. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_summaries` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`periodStartMillis` INTEGER NOT NULL, " +
                "`periodEndMillis` INTEGER NOT NULL, " +
                "`digest` TEXT NOT NULL, " +
                "`episodeCount` INTEGER NOT NULL, " +
                "`actedCount` INTEGER NOT NULL, " +
                "`dismissedCount` INTEGER NOT NULL, " +
                "`createdMillis` INTEGER NOT NULL)",
        )
    }
}

/** v7: Track C4 tool stats + Track B3 episode action summaries. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tool_stats` (" +
                "`tool` TEXT NOT NULL, " +
                "`attempts` INTEGER NOT NULL, " +
                "`successes` INTEGER NOT NULL, " +
                "`updatedMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`tool`))",
        )
        db.execSQL("ALTER TABLE `memory_episodes` ADD COLUMN `actionSummary` TEXT NOT NULL DEFAULT ''")
    }
}

/** v8: Audit A3 — persisted reminders that survive reboot. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reminders` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`fireMillis` INTEGER NOT NULL, " +
                "`createdMillis` INTEGER NOT NULL)",
        )
    }
}

/** v11: behavior triggers — locked overlays she schedules on herself. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `triggers` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`fireMillis` INTEGER NOT NULL, " +
                "`createdMillis` INTEGER NOT NULL, " +
                "`repeatType` TEXT NOT NULL DEFAULT 'none', " +
                "`strict` INTEGER NOT NULL DEFAULT 0)",
        )
    }
}

/** v10: ACT-2 — repeating reminders (repeat type + weekday value). */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `repeatType` TEXT NOT NULL DEFAULT 'none'")
        db.execSQL("ALTER TABLE `reminders` ADD COLUMN `repeatValue` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v9: Dynamic Mood Balancing — persistent valence-arousal mood state. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mood_state` (" +
                "`id` INTEGER NOT NULL, " +
                "`valence` REAL NOT NULL, " +
                "`arousal` REAL NOT NULL, " +
                "`mood` TEXT NOT NULL, " +
                "`intensity` REAL NOT NULL, " +
                "`reason` TEXT NOT NULL, " +
                "`updatedMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}