package com.shiina.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiina.mobile.data.security.KeyStoreKeys
import com.shiina.mobile.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val keyStore: KeyStoreKeys,
) : ViewModel() {

    val screenshotEnabled = settings.screenshotEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val alarmHour = settings.alarmHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 19)

    val alarmMinute = settings.alarmMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _geminiKey = MutableStateFlow(
        keyStore.getKeys("gemini").firstOrNull().orEmpty()
    )
    val geminiKey: StateFlow<String> = _geminiKey

    fun toggleScreenshot(enabled: Boolean) {
        viewModelScope.launch { settings.setScreenshotEnabled(enabled) }
    }

    fun setAlarm(hour: Int, minute: Int) {
        viewModelScope.launch { settings.setAlarm(hour, minute) }
    }

    fun updateGeminiKey(key: String) {
        _geminiKey.value = key
        keyStore.setKeys("gemini", listOf(key.trim()).filter { it.isNotEmpty() })
    }
}
