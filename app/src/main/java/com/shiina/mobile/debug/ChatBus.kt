package com.shiina.mobile.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide talk state shared between DebugTalkService (agent engine) and the
 * in-app Chat UI. Same process, so a simple singleton StateFlow is enough:
 * - [busy]: an agent run is in flight (drives the Stop button + bubble shimmer).
 * - [progress]: latest intermediate agent message (empty when quiet).
 * - [lastUserText]: just sent by the UI, echoed immediately for snappy feedback
 *   (the service persists the DB turn inside askGemini).
 */
object ChatBus {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress.asStateFlow()

    private val _lastUserText = MutableStateFlow<String?>(null)
    val lastUserText: StateFlow<String?> = _lastUserText.asStateFlow()

    @Volatile
    var stopRequested: Boolean = false

    data class SpokenUtterance(
        val text: String,
        val mood: String = "calm",
        val isLateNight: Boolean = false,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val _spokenUtterance = MutableStateFlow<SpokenUtterance?>(null)
    val spokenUtterance: StateFlow<SpokenUtterance?> = _spokenUtterance.asStateFlow()

    fun speak(text: String, mood: String = "calm", isLateNight: Boolean = false) {
        if (text.isNotBlank()) {
            _spokenUtterance.value = SpokenUtterance(text, mood, isLateNight)
        }
    }

    /** Called by the service when a talk run begins (UI may already have set it). */
    fun beginRun() {
        _busy.value = true
        stopRequested = false
        _progress.value = ""
    }

    /** Called by the service when a talk run ends. */
    fun endRun() {
        _busy.value = false
        _progress.value = ""
        stopRequested = false
    }

    fun setProgress(text: String) {
        _progress.value = text.take(200)
    }

    fun echoUser(text: String) {
        _lastUserText.value = text
    }

    fun consumeUserEcho(): String? {
        val v = _lastUserText.value
        _lastUserText.value = null
        return v
    }

    /** UI requests a stop: set the flag the loop checks between turns. */
    fun requestStop() {
        stopRequested = true
    }
}
