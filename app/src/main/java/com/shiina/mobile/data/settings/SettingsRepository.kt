package com.shiina.mobile.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

    /** R4 (Phase 4): notification triage & digest toggle. */
    val notificationTriageEnabled: Flow<Boolean> =
        context.store.data.map { it[Keys.NOTIF_TRIAGE] ?: DEFAULT_NOTIFICATION_TRIAGE }

    suspend fun setNotificationTriageEnabled(enabled: Boolean) {
        context.store.edit { it[Keys.NOTIF_TRIAGE] = enabled }
    }

    /**
     * R4 / AUDIT N1: opt-in to sending (already-truncated) notification title/text
     * into the model prompt. Defaults to OFF (privacy): on a fresh install only
     * counts/senders leave the device until the user explicitly enables this in
     * Settings -> Notifications & privacy.
     */
    val notificationSummarizationEnabled: Flow<Boolean> =
        context.store.data.map { it[Keys.NOTIF_SUMMARY] ?: DEFAULT_NOTIFICATION_SUMMARIZATION }

    suspend fun setNotificationSummarizationEnabled(enabled: Boolean) {
        context.store.edit { it[Keys.NOTIF_SUMMARY] = enabled }
    }

    /** R4: per-category mute, keyed by `TriageCategory.id`. */
    val mutedNotificationCategories: Flow<Set<String>> =
        context.store.data.map { it[Keys.NOTIF_MUTED_CATS] ?: emptySet() }

    suspend fun toggleMutedNotificationCategory(categoryId: String) {
        context.store.edit { prefs ->
            val current = prefs[Keys.NOTIF_MUTED_CATS] ?: emptySet()
            prefs[Keys.NOTIF_MUTED_CATS] =
                if (categoryId in current) current - categoryId else current + categoryId
        }
    }

    /** R4: per-package mute — muted senders never enter the digest. */
    val mutedNotificationPackages: Flow<Set<String>> =
        context.store.data.map { it[Keys.NOTIF_MUTED_PKGS] ?: emptySet() }

    suspend fun toggleMutedNotificationPackage(packageName: String) {
        context.store.edit { prefs ->
            val current = prefs[Keys.NOTIF_MUTED_PKGS] ?: emptySet()
            prefs[Keys.NOTIF_MUTED_PKGS] =
                if (packageName in current) current - packageName else current + packageName
        }
    }

    suspend fun setPresenceOnly(enabled: Boolean) {
        context.store.edit { it[Keys.PRESENCE_ONLY] = enabled }
    }

    companion object {
        const val MODEL_35_FLASH_LITE = "gemini-3.5-flash-lite"
        const val MODEL_31_FLASH_LITE = "gemini-3.1-flash-lite"
        const val DEFAULT_MODEL = MODEL_35_FLASH_LITE

        /** AUDIT N1: sending notification title/text to the model is opt-in. */
        const val DEFAULT_NOTIFICATION_SUMMARIZATION = false

        /** On-device triage/digest is on by default; nothing leaves the device. */
        const val DEFAULT_NOTIFICATION_TRIAGE = true

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
        val NOTIF_TRIAGE = booleanPreferencesKey("notification_triage_enabled")
        val NOTIF_SUMMARY = booleanPreferencesKey("notification_summarization_enabled")
        val NOTIF_MUTED_CATS = stringSetPreferencesKey("notification_muted_categories")
        val NOTIF_MUTED_PKGS = stringSetPreferencesKey("notification_muted_packages")
    }
}