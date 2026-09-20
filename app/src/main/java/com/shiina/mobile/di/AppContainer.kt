package com.shiina.mobile.di

import android.content.Context
import androidx.room.Room
import com.shiina.mobile.data.db.AppDatabase
import com.shiina.mobile.data.db.ChatHistory
import com.shiina.mobile.data.db.MIGRATION_1_2
import com.shiina.mobile.data.db.MIGRATION_2_3
import com.shiina.mobile.data.db.MIGRATION_3_4
import com.shiina.mobile.data.db.MIGRATION_4_5
import com.shiina.mobile.data.db.MIGRATION_5_6
import com.shiina.mobile.data.db.MIGRATION_6_7
import com.shiina.mobile.data.db.MIGRATION_7_8
import com.shiina.mobile.data.security.KeyStoreKeys
import com.shiina.mobile.data.settings.SettingsRepository
import com.shiina.mobile.action.ActionExecutor
import com.shiina.mobile.action.PageReader
import com.shiina.mobile.action.ReminderScheduler
import com.shiina.mobile.action.ToolTracker
import com.shiina.mobile.action.WebSearch
import com.shiina.mobile.decision.GeminiProvider
import com.shiina.mobile.decision.MemoryContext
import com.shiina.mobile.decision.ProviderRegistry
import com.shiina.mobile.decision.RoundRobinKeyPool
import com.shiina.mobile.memory.MemoryCompactor
import com.shiina.mobile.memory.MemoryStore
import com.shiina.mobile.memory.NightlyReflection
import com.shiina.mobile.observation.BaselineUpdater
import com.shiina.mobile.observation.DeviceSenses
import com.shiina.mobile.observation.MemoryOutcomes
import com.shiina.mobile.observation.ScreenshotTaker
import com.shiina.mobile.observation.UsageReader
import okhttp3.OkHttpClient

/** Manual dependency wiring for v1. No DI framework. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "companion.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
            )
            .build()
    }

    val toolStatDao by lazy { database.toolStatDao() }

    val toolTracker: ToolTracker by lazy { ToolTracker(toolStatDao) }

    val musicTracker: com.shiina.mobile.observation.MusicTracker by lazy {
        com.shiina.mobile.observation.MusicTracker(appContext)
    }

    val deviceSenses: DeviceSenses by lazy { DeviceSenses(appContext, musicTracker) }

    val pageReader: PageReader by lazy { PageReader() }

    val reminderScheduler: ReminderScheduler by lazy {
        ReminderScheduler(appContext, database.reminderDao())
    }

    val memoryEpisodeDao by lazy { database.memoryEpisodeDao() }

    val memoryFactDao by lazy { database.memoryFactDao() }

    val chatTurnDao by lazy { database.chatTurnDao() }

    val memorySummaryDao by lazy { database.memorySummaryDao() }

    val memoryOutcomes: MemoryOutcomes by lazy { MemoryOutcomes(memoryEpisodeDao) }

    val memoryStore: MemoryStore by lazy {
        MemoryStore(memoryFactDao, memoryEpisodeDao, chatTurnDao, memorySummaryDao)
    }

    val chatHistory: ChatHistory by lazy { ChatHistory(chatTurnDao, memoryFactDao) }

    val learnedMemoryManager: com.shiina.mobile.memory.LearnedMemoryManager by lazy {
        com.shiina.mobile.memory.LearnedMemoryManager(appContext, memoryStore)
    }

    val memoryContext: MemoryContext by lazy {
        MemoryContext(memoryEpisodeDao, memoryFactDao, database.baselineDao(), memorySummaryDao)
    }

    val memoryCompactor: MemoryCompactor by lazy {
        MemoryCompactor(memoryEpisodeDao, memorySummaryDao, chatTurnDao, database.goalDao())
    }

    val nightlyReflection: NightlyReflection by lazy {
        NightlyReflection(
            memoryEpisodeDao,
            database.sleepDao(),
            database.baselineDao(),
            memoryFactDao,
            memoryStore,
            memoryCompactor,
            toolStatDao,
        )
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(appContext)
    }

    val keyStore: KeyStoreKeys by lazy {
        KeyStoreKeys(appContext)
    }

    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    val providerRegistry: ProviderRegistry by lazy {
        val geminiPool = RoundRobinKeyPool { keyStore.getKeys("gemini") }
        ProviderRegistry(
            listOf(
                GeminiProvider(
                    appContext, geminiPool, httpClient, memoryEpisodeDao,
                    goalDao = database.goalDao(),
                ),
            ),
            memoryEpisodeDao,
            memoryOutcomes,
            memoryContext,
            deviceSenses,
            settingsRepository,
            database.baselineDao(),
        )
    }

    val usageReader: UsageReader by lazy { UsageReader(appContext) }

    val baselineUpdater: BaselineUpdater by lazy {
        BaselineUpdater(database.baselineDao(), database.usageDao())
    }

    val actionExecutor: ActionExecutor by lazy {
        ActionExecutor(
            appContext, settingsRepository, database.goalDao(),
            memoryOutcomes, memoryStore, screenshotTaker, webSearch,
            pageReader, reminderScheduler, toolTracker, memoryEpisodeDao,
        )
    }

    val screenshotTaker: ScreenshotTaker by lazy {
        ScreenshotTaker(appContext)
    }

    val webSearch: WebSearch by lazy { WebSearch() }
}