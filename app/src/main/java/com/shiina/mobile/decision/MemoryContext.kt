package com.shiina.mobile.decision

import com.shiina.mobile.data.db.BaselineDao
import com.shiina.mobile.data.db.MemoryEpisode
import com.shiina.mobile.data.db.MemoryEpisodeDao
import com.shiina.mobile.data.db.MemoryFactDao
import com.shiina.mobile.data.db.MemorySummaryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 4 recall: builds a compact memory block appended to the decision
 * prompt. Hermes-style ranking, not just recency:
 * - identity facts (user_name) pinned first — she uses a name only from here.
 * - episodes ranked by importance (acted > talkedBack > dismissed > shown)
 *   with recency decay, so the rounds that MATTERED surface first.
 * - weekly compaction digests appended so compacted history still speaks.
 * Audit M3: contradicted facts (confidence < 0.3) are kept out of recall.
 * Audit M5: facts ranked by confidence x recency, not confidence alone.
 * Audit M6: "tool:X unreliable" facts become explicit exclusion signals.
 * Audit M7: per-section char budgets so one section can't starve the rest.
 * Always queried on Dispatchers.IO.
 */
class MemoryContext(
    private val episodeDao: MemoryEpisodeDao,
    private val factDao: MemoryFactDao,
    private val baselineDao: BaselineDao,
    private val summaryDao: MemorySummaryDao,
    private val toolStatDao: com.shiina.mobile.data.db.ToolStatDao? = null,
) {

    suspend fun build(): String = withContext(Dispatchers.IO) {
        runCatching { buildInternal() }.getOrDefault("")
    }

    private suspend fun buildInternal(): String {
        val now = System.currentTimeMillis()
        val header = StringBuilder()
        val facts = factDao.topByConfidence(20).filter { it.confidence >= 0.3 }
        val name = facts.firstOrNull { it.key == "user_name" }
            ?: facts.firstOrNull { it.key == "name" }
        if (name != null && name.confidence >= 0.5) {
            header.append("User's name is ${name.value}. Use it naturally. ")
        }
        // M6: tool-reliability facts -> exclusion signals for the loop.
        val unreliable = facts.filter { it.key.startsWith("tool:") }
        if (unreliable.isNotEmpty()) {
            header.append("Avoid these tools (they keep failing): ")
                .append(unreliable.joinToString(", ") { it.key.removePrefix("tool:") })
                .append(". ")
        }
        val episodesSection = StringBuilder()
        val recent = episodeDao.recent(30)
        if (recent.isNotEmpty()) {
            episodesSection.append("Notable rounds: ")
            episodesSection.append(
                recent.sortedByDescending { importance(it, now) }.take(5)
                    .joinToString("; ") { e ->
                        "${e.tone}${if (e.interrupt) "/interrupt" else ""}" +
                            "${outcomeTag(e)} ${e.entertainmentMinutes}min" +
                            (if (e.actionSummary.isNotBlank()) " (${e.actionSummary.take(40)})" else "")
                    },
            )
            episodesSection.append(". ")
            val overDays = consecutiveOverBaselineDays(recent)
            if (overDays >= 2) episodesSection.append("$overDays days in a row over baseline. ")
            val dismissStreak = recent.takeWhile { it.dismissed }.size
            if (dismissStreak >= 1) episodesSection.append("Dismissed last $dismissStreak interrupt(s). ")
        }
        // M5: rank remaining facts by confidence x recency.
        val rest = facts
            .filter { it.key != "user_name" && it.key != "name" && !it.key.startsWith("tool:") }
            .sortedByDescending { factScore(it.confidence, it.updatedMillis, now) }
            .take(10)
        val factsSection = StringBuilder()
        if (rest.isNotEmpty()) {
            factsSection.append("Known facts: ")
                .append(rest.joinToString("; ") { "${it.key}=${it.value}" })
                .append(". ")
        }
        val digestsSection = StringBuilder()
        val digests = summaryDao.recent(2)
        if (digests.isNotEmpty()) {
            digestsSection.append("Past weeks: ")
                .append(digests.joinToString(" ") { it.digest.take(200) })
                .append(" ")
        }
        val learned = StringBuilder()
        baselineDao.get(KEY_THRESHOLD)?.let {
            learned.append("Learned interrupt threshold: ${it.average.toInt()}% over baseline. ")
        }
        baselineDao.get(KEY_NUDGE_HOUR)?.let {
            learned.append("Best nudge hour: ${it.average.toInt()}:00. ")
        }
        // MEM-3: surface the historically most effective tone.
        runCatching {
            toolStatDao?.all()?.filter { it.tool.startsWith("tone:") && it.attempts >= 3 }
                ?.maxByOrNull { s -> if (s.attempts > 0) s.successes.toDouble() / s.attempts else 0.0 }
                ?.let { learned.append("Most effective tone so far: ${it.tool.removePrefix("tone:")}. ") }
        }
        // M7: per-section budgets so no single section starves the rest.
        return (header.toString().take(300) +
            episodesSection.toString().take(600) +
            factsSection.toString().take(800) +
            digestsSection.toString().take(400) +
            learned.toString().take(200)).take(2000)
    }

    private fun factScore(confidence: Double, updatedMillis: Long, now: Long): Double {
        val ageDays = ((now - updatedMillis).coerceAtLeast(0L) / 86_400_000.0)
        return confidence * (1.0 + 1.0 / (1.0 + ageDays))
    }

    /**
     * Importance: outcomes first, recency-decayed. Acted-on rounds outrank
     * everything; fresh rounds outrank stale ones with equal outcomes.
     */
    private fun importance(e: MemoryEpisode, now: Long): Double {
        var w = 0.0
        if (e.acted) w += 3.0
        if (e.talkedBack) w += 2.0
        if (e.dismissed) w += 1.0
        if (e.interrupt) w += 1.0
        else if (e.shown) w += 0.5
        val ageDays = ((now - e.timestampMillis).coerceAtLeast(0L) / 86_400_000.0)
        return w + 2.0 / (1.0 + ageDays)
    }

    private fun outcomeTag(e: MemoryEpisode): String = when {
        e.acted -> "/acted"
        e.talkedBack -> "/talked"
        e.dismissed -> "/dismissed"
        e.shown -> "/shown"
        else -> ""
    }

    /** Consecutive most-recent days whose latest round was over baseline. */
    private fun consecutiveOverBaselineDays(recent: List<MemoryEpisode>): Int {
        val byDay = recent.groupBy { it.timestampMillis / 86_400_000L }
            .mapValues { (_, v) -> v.maxByOrNull { ep -> ep.timestampMillis }!! }
        var streak = 0
        var day = System.currentTimeMillis() / 86_400_000L
        while (true) {
            val ep = byDay[day] ?: break
            if (ep.entertainmentMinutes > ep.entertainmentBaseline) streak++ else break
            day--
        }
        return streak
    }

    companion object {
        const val KEY_THRESHOLD = "interrupt_threshold_pct"
        const val KEY_NUDGE_HOUR = "best_nudge_hour"
    }
}