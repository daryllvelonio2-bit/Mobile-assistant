package com.shiina.mobile.memory

import com.shiina.mobile.data.db.ChatTurnDao
import com.shiina.mobile.data.db.GoalDao
import com.shiina.mobile.data.db.MemoryEpisode
import com.shiina.mobile.data.db.MemoryEpisodeDao
import com.shiina.mobile.data.db.MemorySummary
import com.shiina.mobile.data.db.MemorySummaryDao
import com.shiina.mobile.debug.AppDebugServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto-compaction, Hermes-style: extract-then-delete. Episodes older than 30
 * days are grouped oldest-first into 7-day windows; each window is distilled
 * into a rule-based digest row (counts, outcome rates, dominant tone, screen
 * vs baseline, goals, best moment) and ONLY THEN are the raw rows deleted.
 * Fully offline and deterministic — no API key needed, runs inside the
 * nightly charger-idle worker, bounded to 4 windows per run.
 * Audit M10: acted episodes never lose their substance — their action
 * summaries ride into the digest. Audit M11: goal TITLES completed in the
 * window are named, not just counted.
 */
class MemoryCompactor(
    private val episodeDao: MemoryEpisodeDao,
    private val summaryDao: MemorySummaryDao,
    private val chatDao: ChatTurnDao,
    private val goalDao: GoalDao? = null,
) {

    suspend fun run(now: Long = System.currentTimeMillis()) {
        var windows = 0
        while (windows < MAX_WINDOWS_PER_RUN) {
            val oldest = episodeDao.oldestBefore(now - COMPACT_AFTER_MS) ?: break
            val windowEnd = oldest.timestampMillis + WINDOW_MS
            val window = episodeDao.episodesInRange(oldest.timestampMillis, windowEnd)
            if (window.isEmpty()) break
            val start = window.minOf { it.timestampMillis }
            val end = window.maxOf { it.timestampMillis }
            val talkCount = runCatching { chatDao.countInRange(start, end) }.getOrDefault(0)
            var digest = distill(window, start, end)
            if (talkCount > 0) digest += " $talkCount talk exchanges."
            summaryDao.insert(
                MemorySummary(
                    periodStartMillis = start,
                    periodEndMillis = end,
                    digest = digest.take(560),
                    episodeCount = window.size,
                    actedCount = window.count { it.acted },
                    dismissedCount = window.count { it.dismissed },
                    createdMillis = now,
                ),
            )
            episodeDao.deleteOlderThan(windowEnd)
            windows++
            AppDebugServer.log("MEMORY", "Compacted ${window.size} episodes -> digest: $digest")
        }
        if (windows > 0) {
            AppDebugServer.log("MEMORY", "Compaction done: $windows window(s), ${summaryDao.count()} digests kept")
        }
    }

    private suspend fun distill(window: List<MemoryEpisode>, start: Long, end: Long): String {
        val interrupts = window.count { it.interrupt }
        val acted = window.count { it.acted }
        val talked = window.count { it.talkedBack }
        val dismissed = window.count { it.dismissed }
        val tone = window.groupingBy { it.tone }.eachCount()
            .maxByOrNull { it.value }?.key ?: "neutral"
        val avgMin = window.map { it.entertainmentMinutes }.average().toInt()
        val avgBase = window.map { it.entertainmentBaseline }.average().toInt()
        val goalsDone = window.sumOf { it.goalsDone }
        val sb = StringBuilder(
            "Week of ${fmtDay(window.minOf { it.timestampMillis })}: " +
                "${window.size} rounds, $interrupts interrupts " +
                "($acted acted, $talked talked back, $dismissed dismissed). " +
                "Tone mostly $tone. Screen ~${avgMin}min vs ${avgBase}min baseline. " +
                "$goalsDone goals done.",
        )
        // M10: preserve what she actually did — acted rounds keep their summaries.
        window.filter { it.acted && it.actionSummary.isNotBlank() }
            .sortedByDescending { it.timestampMillis }.take(2)
            .forEach { sb.append(" Did: ${it.actionSummary.take(100)}.") }
        // M11: name the goals finished in this window, not just the count.
        runCatching {
            goalDao?.all()?.filter { it.status == 1 && it.updatedMillis in start..end }
                ?.take(3)?.joinToString { it.title.take(28) }
                ?.takeIf { it.isNotBlank() }
                ?.let { sb.append(" Finished: $it.") }
        }
        window.filter { it.acted }.maxByOrNull { it.timestampMillis }?.let { best ->
            sb.append(" Best: ${fmtDay(best.timestampMillis)} ${best.tone} led to action.")
        }
        return sb.toString()
    }

    private fun fmtDay(millis: Long): String =
        SimpleDateFormat("MMM d", Locale.US).format(Date(millis))

    companion object {
        private const val COMPACT_AFTER_MS = 30L * 86_400_000L
        private const val WINDOW_MS = 7L * 86_400_000L
        private const val MAX_WINDOWS_PER_RUN = 4
    }
}