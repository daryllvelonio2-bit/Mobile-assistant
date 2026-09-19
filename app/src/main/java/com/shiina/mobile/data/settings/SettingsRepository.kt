package com.shiina.mobile.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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

    suspend fun setScreenshotEnabled(enabled: Boolean) {
        context.store.edit { it[Keys.SCREENSHOT] = enabled }
    }

    suspend fun setAlarm(hour: Int, minute: Int) {
        context.store.edit {
            it[Keys.ALARM_HOUR] = hour
            it[Keys.ALARM_MINUTE] = minute
        }
    }

    private object Keys {
        val SCREENSHOT = booleanPreferencesKey("screenshot_enabled")
        val ALARM_HOUR = intPreferencesKey("alarm_hour")
        val ALARM_MINUTE = intPreferencesKey("alarm_minute")
        val PROVIDER_ORDER = stringPreferencesKey("provider_order")
    }
}
