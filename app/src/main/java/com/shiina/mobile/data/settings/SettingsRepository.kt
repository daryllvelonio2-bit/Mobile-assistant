package com.shiina.mobile.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

/** Simple persisted settings. Heavy state lives in Room. */
class SettingsRepository(private val context: Context) {

    val screenshotEnabled: Flow<Boolean> =
        context.store.data.map { it[Keys.SCREENSHOT] ?: false }

    val alarmHour: Flow<Int> =
        context.store.data.map { it[Keys.ALARM_HOUR] ?: 19 }

    val alarmMinute: Flow<Int> =
        context.store.data.map { it[Keys.ALARM_MINUTE] ?: 0 }

    /** Audit F12: presence-only kill switch — disables all interrupts. */
    val presenceOnly: Flow<Boolean> =
        context.store.data.map { it[Keys.PRESENCE_ONLY] ?: false }

    val geminiModel: Flow<String> =
        context.store.data.map { it[Keys.GEMINI_MODEL] ?: DEFAULT_MODEL }

    suspend fun getGeminiModel(): String =
        context.store.data.map { it[Keys.GEMINI_MODEL] ?: DEFAULT_MODEL }.first()

    suspend fun setGeminiModel(model: String) {
        context.store.edit { it[Keys.GEMINI_MODEL] = model }
    }

    suspend fun setScreenshotEnabled(enabled: Boolean) {
        context.store.edit { it[Keys.SCREENSHOT] = enabled }
    }

    suspend fun setAlarm(hour: Int, minute: Int) {
        context.store.edit {
            it[Keys.ALARM_HOUR] = hour
            it[Keys.ALARM_MINUTE] = minute
        }
    }

    suspend fun setPresenceOnly(enabled: Boolean) {
        context.store.edit { it[Keys.PRESENCE_ONLY] = enabled }
    }

    companion object {
        const val MODEL_35_FLASH_LITE = "gemini-3.5-flash-lite"
        const val MODEL_31_FLASH_LITE = "gemini-3.1-flash-lite"
        const val DEFAULT_MODEL = MODEL_35_FLASH_LITE

        val AVAILABLE_MODELS = listOf(
            MODEL_35_FLASH_LITE,
            MODEL_31_FLASH_LITE,
        )
    }

    private object Keys {
        val SCREENSHOT = booleanPreferencesKey("screenshot_enabled")
        val ALARM_HOUR = intPreferencesKey("alarm_hour")
        val ALARM_MINUTE = intPreferencesKey("alarm_minute")
        val PROVIDER_ORDER = stringPreferencesKey("provider_order")
        val PRESENCE_ONLY = booleanPreferencesKey("presence_only")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
    }
}