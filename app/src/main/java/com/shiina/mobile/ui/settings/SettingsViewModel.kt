package com.shiina.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiina.mobile.data.db.MemoryFact
import com.shiina.mobile.data.db.MemorySummary
import com.shiina.mobile.data.security.KeyStoreKeys
import com.shiina.mobile.data.settings.SettingsRepository
import com.shiina.mobile.decision.MemoryContext
import com.shiina.mobile.memory.MemoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val keyStore: KeyStoreKeys,
    private val memoryStore: MemoryStore,
    private val memoryContext: MemoryContext? = null,
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

    private val _facts = MutableStateFlow<List<MemoryFact>>(emptyList())
    val facts: StateFlow<List<MemoryFact>> = _facts

    private val _memoryStatus = MutableStateFlow("Memory loading…")
    val memoryStatus: StateFlow<String> = _memoryStatus

    private val _digests = MutableStateFlow<List<MemorySummary>>(emptyList())
    val digests: StateFlow<List<MemorySummary>> = _digests

    /** Exact block she carries into prompts (MemoryContext.build, 2000 chars max). */
    private val _fullContext = MutableStateFlow("Total context not loaded yet.")
    val fullContext: StateFlow<String> = _fullContext

    init {
        loadFacts()
    }

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

    fun loadFacts() {
        viewModelScope.launch {
            _facts.value = runCatching { memoryStore.allFacts() }.getOrDefault(emptyList())
            _memoryStatus.value =
                runCatching { memoryStore.statusLine() }.getOrDefault("Memory unavailable.")
            _digests.value = runCatching { memoryStore.recentDigests() }.getOrDefault(emptyList())
            refreshContext()
        }
    }

    fun refreshContext() {
        viewModelScope.launch {
            _fullContext.value = memoryContext?.build()?.takeIf { it.isNotBlank() }
                ?: "(empty — she remembers nothing right now)"
        }
    }

    fun deleteFact(key: String) {
        viewModelScope.launch {
            runCatching { memoryStore.deleteFact(key) }
            loadFacts()
        }
    }

    fun updateFact(key: String, value: String) {
        viewModelScope.launch {
            runCatching { memoryStore.updateFact(key, value) }
            loadFacts()
        }
    }

    fun forgetAll() {
        viewModelScope.launch {
            runCatching { memoryStore.forgetAll() }
            loadFacts()
        }
    }
}