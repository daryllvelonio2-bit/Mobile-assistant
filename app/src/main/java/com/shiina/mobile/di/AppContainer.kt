package com.shiina.mobile.di

import android.content.Context
import androidx.room.Room
import com.shiina.mobile.data.db.AppDatabase
import com.shiina.mobile.data.security.KeyStoreKeys
import com.shiina.mobile.data.settings.SettingsRepository
import com.shiina.mobile.decision.GeminiProvider
import com.shiina.mobile.decision.ProviderRegistry
import com.shiina.mobile.decision.RoundRobinKeyPool
import com.shiina.mobile.observation.BaselineUpdater
import com.shiina.mobile.observation.UsageReader
import okhttp3.OkHttpClient

/** Manual dependency wiring for v1. No DI framework. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy {
        Room.databaseBuilder(appContext, AppDatabase::class.java, "companion.db").build()
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
        ProviderRegistry(listOf(GeminiProvider(geminiPool, httpClient)))
    }

    val usageReader: UsageReader by lazy { UsageReader(appContext) }

    val baselineUpdater: BaselineUpdater by lazy {
        BaselineUpdater(database.baselineDao(), database.usageDao())
    }
}
