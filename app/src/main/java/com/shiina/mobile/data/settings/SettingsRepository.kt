package com.shiina.mobile.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.store by preferencesDataStore("settings")

/** Simple persisted settings. Heavy state lives in Room. */
class SettingsRepository(private val context: Context) {

    init {
        CoroutineScope(Dispatchers.IO).launch {
            context.store.data.collect { prefs ->
                val sHour = prefs[Keys.BEDTIME_START_HOUR] ?: BedtimeStore.DEFAULT_START_HOUR
                val sMin = prefs[Keys.BEDTIME_START_MINUTE] ?: BedtimeStore.DEFAULT_START_MINUTE
                val eHour = prefs[Keys.BEDTIME_END_HOUR] ?: BedtimeStore.DEFAULT_END_HOUR
                val eMin = prefs[Keys.BEDTIME_END_MINUTE] ?: BedtimeStore.DEFAULT_END_MINUTE
                BedtimeStore.setBedtime(sHour, sMin, eHour, eMin)
            }
        }
    }

    val screenshotEnabled: Flow<Boolean> =
        context.store.data.map { it[Keys.SCREENSHOT] ?: false }

    val alarmHour: Flow<Int> =
        context.store.data.map { it[Keys.ALARM_HOUR] ?: 19 }

    val alarmMinute: Flow<Int> =
        context.store.data.map { it[Keys.ALARM_MINUTE] ?: 0 }

    val bedtimeStartHour: Flow<Int> =
        context.store.data.map { it[Keys.BEDTIME_START_HOUR] ?: BedtimeStore.DEFAULT_START_HOUR }

    val bedtimeStartMinute: Flow<Int> =
        context.store.data.map { it[Keys.BEDTIME_START_MINUTE] ?: BedtimeStore.DEFAULT_START_MINUTE }

    val bedtimeEndHour: Flow<Int> =
        context.store.data.map { it[Keys.BEDTIME_END_HOUR] ?: BedtimeStore.DEFAULT_END_HOUR }

    val bedtimeEndMinute: Flow<Int> =
        context.store.data.map { it[Keys.BEDTIME_END_MINUTE] ?: BedtimeStore.DEFAULT_END_MINUTE }

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

    suspend fun getBedtime(): Bedtime =
        context.store.data.map { prefs ->
            Bedtime(
                startHour = prefs[Keys.BEDTIME_START_HOUR] ?: BedtimeStore.DEFAULT_START_HOUR,
                startMinute = prefs[Keys.BEDTIME_START_MINUTE] ?: BedtimeStore.DEFAULT_START_MINUTE,
                endHour = prefs[Keys.BEDTIME_END_HOUR] ?: BedtimeStore.DEFAULT_END_HOUR,
                endMinute = prefs[Keys.BEDTIME_END_MINUTE] ?: BedtimeStore.DEFAULT_END_MINUTE,
            )
        }.first()

    suspend fun setBedtimeStart(hour: Int, minute: Int) {
        context.store.edit {
            it[Keys.BEDTIME_START_HOUR] = hour
            it[Keys.BEDTIME_START_MINUTE] = minute
        }
        BedtimeStore.setBedtimeStart(hour, minute)
    }

    suspend fun setBedtimeEnd(hour: Int, minute: Int) {
        context.store.edit {
            it[Keys.BEDTIME_END_HOUR] = hour
            it[Keys.BEDTIME_END_MINUTE] = minute
        }
        BedtimeStore.setBedtimeEnd(hour, minute)
    }

    suspend fun setBedtime(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
        context.store.edit {
            it[Keys.BEDTIME_START_HOUR] = startHour
            it[Keys.BEDTIME_START_MINUTE] = startMinute
            it[Keys.BEDTIME_END_HOUR] = endHour
            it[Keys.BEDTIME_END_MINUTE] = endMinute
        }
        BedtimeStore.setBedtime(startHour, startMinute, endHour, endMinute)
    }

    /** Voice output (TTS) toggle on final responses (Backlog B5). */
    val voiceTtsEnabled: Flow<Boolean> =
        context.store.data.map { it[Keys.VOICE_TTS] ?: true }

    suspend fun setVoiceTtsEnabled(enabled: Boolean) {
        context.store.edit { it[Keys.VOICE_TTS] = enabled }
    }

    /** Cloned voice (Backlog B5-clone). System TTS is the emergency fallback. */
    val cloneVoiceEnabled: Flow<Boolean> =
        context.store.data.map { it[Keys.CLONE_VOICE] ?: true }

    suspend fun setCloneVoiceEnabled(enabled: Boolean) {
        context.store.edit { it[Keys.CLONE_VOICE] = enabled }
    }

    /** First-run guided onboarding completion flag (Backlog B8). */
    val onboardingCompleted: Flow<Boolean> =
        context.store.data.map { it[Keys.ONBOARDING_COMPLETED] ?: false }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.store.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
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
        val VOICE_TTS = booleanPreferencesKey("voice_tts_enabled")
        val CLONE_VOICE = booleanPreferencesKey("clone_voice_enabled")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val ALARM_HOUR = intPreferencesKey("alarm_hour")
        val ALARM_MINUTE = intPreferencesKey("alarm_minute")
        val BEDTIME_START_HOUR = intPreferencesKey("bedtime_start_hour")
        val BEDTIME_START_MINUTE = intPreferencesKey("bedtime_start_minute")
        val BEDTIME_END_HOUR = intPreferencesKey("bedtime_end_hour")
        val BEDTIME_END_MINUTE = intPreferencesKey("bedtime_end_minute")
        val PROVIDER_ORDER = stringPreferencesKey("provider_order")
        val PRESENCE_ONLY = booleanPreferencesKey("presence_only")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
    }
}