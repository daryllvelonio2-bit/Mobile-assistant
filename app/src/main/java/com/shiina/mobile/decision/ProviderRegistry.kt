package com.shiina.mobile.decision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tries providers in order. Any failure moves to the next provider.
 * When all are down, returns the rule-based fallback (baseline-only tone, no interrupt).
 */
class ProviderRegistry(private val providers: List<DecisionProvider>) {

    suspend fun decide(summary: DecisionSummary): Decision = withContext(Dispatchers.IO) {
        for (provider in providers) {
            try {
                return@withContext provider.generate(summary)
            } catch (_: Exception) {
                continue
            }
        }
        fallback(summary)
    }

    private fun fallback(summary: DecisionSummary): Decision {
        val drifted = summary.entertainmentMinutes > summary.entertainmentBaseline
        val tone = if (drifted) "steady" else "calm"
        return Decision(tone = tone, interrupt = false, action = "none")
    }
}
