package com.shiina.mobile.decision

import com.shiina.mobile.data.db.BaselineDao
import com.shiina.mobile.data.db.MemoryEpisode
import com.shiina.mobile.data.db.MemoryEpisodeDao
import com.shiina.mobile.data.settings.SettingsRepository
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.observation.DeviceSenses
import com.shiina.mobile.observation.MemoryOutcomes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Tries providers in order. Any failure moves to the next provider.
 * When all are down, returns the rule-based fallback (baseline-only tone, no interrupt).
 * Every round (provider or fallback) is logged as a MemoryEpisode.
 * Phase 2: real cooldown — if she was dismissed twice recently, a new
 * interrupt is suppressed and the suppression itself is logged.
 * Phase 4: memory context is built once per round and handed to providers.
 * Track A1: device senses JSON built once per round and handed to providers.
 * Track C2: confidence floor — below the floor she stays silent.
 * Audit F1: floor is adaptive — a learned high threshold raises the bar.
 * Audit F5: screen off + no music means the user is absent: never interrupt.
 * Audit F12: presence-only kill switch from Settings disables all interrupts.
 * Audit C11: one structured DecisionLog line per round (source, tone,
 * confidence, latency, prompt version) instead of scattered debug strings.
 */
class ProviderRegistry(
    private val providers: List<DecisionProvider>,
    private val episodeDao: MemoryEpisodeDao? = null,
    private val outcomes: MemoryOutcomes? = null,
    private val memoryContext: MemoryContext? = null,
    private val senses: DeviceSenses? = null,
    private val settings: SettingsRepository? = null,
    private val baselineDao: BaselineDao? = null,
) {

    suspend fun decide(summary: DecisionSummary, source: String = "unknown"): Decision =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val context = runCatching { memoryContext?.build().orEmpty() }.getOrDefault("")
            val senseJson = runCatching { senses?.snapshot().orEmpty() }.getOrDefault("")
            var generated: Decision? = null
            for (provider in providers) {
                try {
                    generated = provider.generate(summary, context, senseJson)
                    break
                } catch (_: Exception) {
                    continue
                }
            }
            var final = generated ?: fallback(summary)
            // F5: user absent — screen off and nothing playing. Never interrupt.
            val absent = senseJson.contains("\"screen\":\"off\"") &&
                senseJson.contains("\"music_playing\":false")
            if (final.interrupt && absent) {
                AppDebugServer.log("DECISION", "Absence gate: screen off, no music — staying silent")
                final = silence(final)
            }
            if (final.interrupt && final.confidence < confidenceFloor()) {
                AppDebugServer.log(
                    "DECISION",
                    "Confidence gate: ${final.confidence} < floor — staying silent",
                )
                final = silence(final)
            }
            if (final.interrupt && outcomes?.cooldownActive() == true) {
                AppDebugServer.log("MEMORY", "Cooldown suppression: interrupt blocked after dismissals")
                final = silence(final)
            }
            // F12: presence-only mode — the kill switch from Settings.
            if (final.interrupt && presenceOnly()) {
                AppDebugServer.log("DECISION", "Presence-only mode: interrupt suppressed")
                final = silence(final)
            }
            logEpisode(summary, final, source, usedFallback = generated == null)
            // C11: one structured line per round for regression debugging.
            AppDebugServer.log(
                "DECISION_LOG",
                "source=$source tone=${final.tone} interrupt=${final.interrupt} " +
                    "confidence=${final.confidence} action=${final.action} " +
                    "latencyMs=${System.currentTimeMillis() - started} " +
                    "prompt=${ShiinaPrompts.PROMPT_VERSION} fallback=${generated == null}",
            )
            final
        }

    private fun silence(d: Decision): Decision = d.copy(
        interrupt = false, action = "NONE", actionParam = "",
        message = "", extraActions = emptyList(),
    )

    /** F1: adaptive floor — learned threshold >=30% -> 0.7, <=15% -> 0.5, else 0.6. */
    private suspend fun confidenceFloor(): Double {
        val threshold = runCatching {
            baselineDao?.get(MemoryContext.KEY_THRESHOLD)?.average
        }.getOrNull() ?: return CONFIDENCE_FLOOR
        return when {
            threshold >= 30.0 -> 0.7
            threshold <= 15.0 -> 0.5
            else -> CONFIDENCE_FLOOR
        }
    }

    private suspend fun presenceOnly(): Boolean = runCatching {
        settings?.presenceOnly?.first() ?: false
    }.getOrDefault(false)

    private suspend fun logEpisode(
        summary: DecisionSummary,
        decision: Decision,
        source: String,
        usedFallback: Boolean,
    ) {
        val dao = episodeDao ?: return
        runCatching {
            dao.insert(
                MemoryEpisode(
                    timestampMillis = System.currentTimeMillis(),
                    source = if (usedFallback) "fallback" else source,
                    entertainmentMinutes = summary.entertainmentMinutes,
                    entertainmentBaseline = summary.entertainmentBaseline,
                    goalsOpen = summary.goalsOpen,
                    goalsDone = summary.goalsDone,
                    goalsMissed = summary.goalsMissed,
                    tone = decision.tone,
                    interrupt = decision.interrupt,
                    action = decision.action,
                    messageHash = decision.message.hashCode(),
                ),
            )
            AppDebugServer.log(
                "MEMORY",
                "Episode logged: source=${if (usedFallback) "fallback" else source} " +
                    "tone=${decision.tone} interrupt=${decision.interrupt} " +
                    "confidence=${decision.confidence}",
            )
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "Episode log failed: ${e.message}")
        }
    }

    private fun fallback(summary: DecisionSummary): Decision {
        val drifted = summary.entertainmentMinutes > summary.entertainmentBaseline
        val tone = if (drifted) "candid_direct" else "neutral"
        val message = if (drifted) {
            "Screen time ${summary.entertainmentMinutes}min is over your " +
                "${summary.entertainmentBaseline.toInt()}min baseline. Time to wind down?"
        } else ""
        return Decision.fallback(tone, message)
    }

    companion object {
        const val CONFIDENCE_FLOOR = 0.6
    }
}