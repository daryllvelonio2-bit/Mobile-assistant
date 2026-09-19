package com.shiina.mobile.ui.character

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiina.mobile.character.CharacterMode
import com.shiina.mobile.character.CharacterOverlayService
import com.shiina.mobile.decision.DecisionSummary
import com.shiina.mobile.decision.ProviderRegistry
import com.shiina.mobile.observation.BaselineUpdater
import com.shiina.mobile.observation.UsageReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CharacterViewModel(
    private val appContext: Context,
    private val registry: ProviderRegistry,
    private val usageReader: UsageReader,
    private val baselineUpdater: BaselineUpdater,
) : ViewModel() {

    private val _tone = MutableStateFlow("calm")
    val tone: StateFlow<String> = _tone

    private val _mode = MutableStateFlow(CharacterMode.STAY)
    val mode: StateFlow<CharacterMode> = _mode

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun canOverlay(): Boolean = Settings.canDrawOverlays(appContext)

    fun clearError() {
        _error.value = null
    }

    fun show() {
        if (!canOverlay()) {
            _error.value = "Overlay permission not granted."
            com.shiina.mobile.debug.AppDebugServer.log("UI", "Show failed: Overlay permission not granted")
            return
        }
        runCatching {
            val intent = Intent(appContext, CharacterOverlayService::class.java).apply {
                action = CharacterOverlayService.ACTION_SHOW
                putExtra(CharacterOverlayService.EXTRA_TONE, _tone.value)
                putExtra(CharacterOverlayService.EXTRA_MODE, _mode.value.name)
            }
            appContext.startForegroundService(intent)
            com.shiina.mobile.debug.AppDebugServer.log("UI", "Started CharacterOverlayService (SHOW)")
        }.onFailure { e ->
            val err = "Show failed: ${e.message ?: e::class.simpleName}"
            _error.value = err
            com.shiina.mobile.debug.AppDebugServer.log("ERROR", err)
        }
    }

    fun hide() {
        runCatching {
            val intent = Intent(appContext, CharacterOverlayService::class.java).apply {
                action = CharacterOverlayService.ACTION_HIDE
            }
            appContext.startService(intent)
            com.shiina.mobile.debug.AppDebugServer.log("UI", "Started CharacterOverlayService (HIDE)")
        }.onFailure { e ->
            val err = "Hide failed: ${e.message ?: e::class.simpleName}"
            _error.value = err
            com.shiina.mobile.debug.AppDebugServer.log("ERROR", err)
            return
        }
        _mode.value = CharacterMode.VANISH
    }

    fun renderLatest() {
        com.shiina.mobile.debug.AppDebugServer.log("UI", "renderLatest() clicked")
        viewModelScope.launch {
            try {
                _error.value = null
                val minutes = withContext(Dispatchers.IO) {
                    usageReader.getTodayEntertainmentMinutes()
                }
                val baseline = baselineUpdater.getEntertainmentBaseline()
                com.shiina.mobile.debug.AppDebugServer.log("LOGIC", "Usage minutes: $minutes, baseline: $baseline")
                val decision = registry.decide(
                    DecisionSummary(
                        entertainmentMinutes = minutes,
                        entertainmentBaseline = baseline,
                        sleepBedMillis = 0L,
                        sleepBaselineMillis = 0L,
                        goalsOpen = 0,
                        goalsDone = 0,
                        goalsMissed = 0,
                    ),
                )
                com.shiina.mobile.debug.AppDebugServer.log("DECISION", "Decision received: tone=${decision.tone}, interrupt=${decision.interrupt}")
                _tone.value = decision.tone
                _mode.value = if (decision.interrupt) CharacterMode.WANDER else CharacterMode.STAY
                show()
            } catch (e: Exception) {
                val err = "Render failed: ${e.message ?: e::class.simpleName}"
                _error.value = err
                com.shiina.mobile.debug.AppDebugServer.log("ERROR", "$err\n${e.stackTraceToString()}")
            }
        }
    }
}
