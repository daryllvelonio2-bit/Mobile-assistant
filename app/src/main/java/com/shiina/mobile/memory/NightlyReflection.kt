package com.shiina.mobile.memory

import com.shiina.mobile.data.db.BaselineDao
import com.shiina.mobile.data.db.BaselineSnapshot
import com.shiina.mobile.data.db.MemoryEpisode
import com.shiina.mobile.data.db.MemoryEpisodeDao
import com.shiina.mobile.data.db.MemoryFactDao
import com.shiina.mobile.data.db.SleepDao
import com.shiina.mobile.data.db.ToolStatDao
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.decision.MemoryContext
import java.util.Calendar

/**
 * Phase 5 nightly adaptation — ratios and medians only, no ML library.
 * Runs from the charger-idle WorkManager worker:
 * - per-tone effectiveness (acted+talkedBack rate, 14 days)
 * - adaptive interrupt threshold drifting toward where the user responds
 * - best nudge hour (hour with most acted interrupts)
 * - bedtime learned from median screen-off time
 * - prune: episodes >90d, weak stale facts
 * Audit M12: inferred facts untouched for 14+ days decay -0.05/night so
 * stale guesses fade out instead of squatting in recall forever.
 */
class NightlyReflection(
    private val episodeDao: MemoryEpisodeDao,
    private val sleepDao: SleepDao,
    private val baselineDao: BaselineDao,
    private val factDao: MemoryFactDao,
    private val memoryStore: MemoryStore,
    private val compactor: MemoryCompactor,
    private val toolStatDao: ToolStatDao? = null,
    private val memoryConsolidator: MemoryConsolidator? = null,
) {

    suspend fun run() {
        val now = System.currentTimeMillis()
        val episodes = episodeDao.episodesSince(now - 14L * 86_400_000L)
        if (episodes.isNotEmpty()) {
            toneEffectiveness(episodes)
            adaptThreshold(episodes, now)
            bestNudgeHour(episodes, now)
        }
        learnBedtime()
        decayStaleFacts(now)
        toolStatsReport()
        runCatching { compactor.run(now) }.onFailure { e ->
            AppDebugServer.log("ERROR", "Compaction failed: ${e.message}")
        }
        runCatching { memoryConsolidator?.consolidate(force = true) }.onFailure { e ->
            AppDebugServer.log("ERROR", "Nightly consolidation failed: ${e.message}")
        }
        prune(now)
        AppDebugServer.log("MEMORY", "NightlyReflection done (${episodes.size} episodes reviewed)")
    }

    private fun toneEffectiveness(episodes: List<MemoryEpisode>) {
        episodes.filter { it.interrupt }.groupBy { it.tone }.forEach { (tone, list) ->
            val landed = list.count { it.acted || it.talkedBack }
            AppDebugServer.log("MEMORY", "Tone $tone effectiveness: $landed/${list.size} landed")
        }
    }

    /** Drift the interrupt bar from 20% toward the level where the user responds (10..40%). */
    private suspend fun adaptThreshold(episodes: List<MemoryEpisode>, now: Long) {
        val responded = episodes.filter { it.interrupt && (it.acted || it.talkedBack) }
        if (responded.isEmpty()) return
        val current = baselineDao.get(MemoryContext.KEY_THRESHOLD)?.average ?: 20.0
        val targetPct = median(
            responded.map { e ->
                val base = e.entertainmentBaseline.coerceAtLeast(1.0)
                (e.entertainmentMinutes - base) / base * 100.0
            },
        )
        val next = (0.7 * current + 0.3 * targetPct).coerceIn(10.0, 40.0)
        baselineDao.upsert(BaselineSnapshot(MemoryContext.KEY_THRESHOLD, next, now))
        AppDebugServer.log("MEMORY", "Interrupt threshold ${current.toInt()}% -> ${next.toInt()}%")
    }

    private suspend fun bestNudgeHour(episodes: List<MemoryEpisode>, now: Long) {
        val hour = episodes.filter { it.acted }
            .groupingBy { hourOf(it.timestampMillis) }
            .eachCount()
            .maxByOrNull { it.value }?.key ?: return
        baselineDao.upsert(BaselineSnapshot(MemoryContext.KEY_NUDGE_HOUR, hour.toDouble(), now))
        AppDebugServer.log("MEMORY", "Best nudge hour: $hour:00")
    }

    /** Median bed time from the last 14 sleep records -> inferred fact. */
    private suspend fun learnBedtime() {
        val records = sleepDao.recent(14)
        if (records.size < 3) return
        val minutesOfDay = records.map { r ->
            val c = Calendar.getInstance().apply { timeInMillis = r.bedMillis }
            c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        }
        val medianMin = median(minutesOfDay.map { it.toDouble() }).toInt()
        memoryStore.remember("bedtime", "%02d:%02d".format(medianMin / 60, medianMin % 60), "inferred")
    }

    /** M12: inferred facts untouched 14+ days lose 0.05 confidence per night. */
    private suspend fun decayStaleFacts(now: Long) {
        runCatching {
            val cutoff = now - 14L * 86_400_000L
            factDao.all()
                .filter { it.source == "inferred" && it.updatedMillis < cutoff && it.confidence > 0.05 }
                .forEach { f ->
                    val next = (f.confidence - 0.05).coerceAtLeast(0.05)
                    factDao.upsert(f.copy(confidence = next))
                }
        }.onFailure { e -> AppDebugServer.log("ERROR", "Fact decay failed: ${e.message}") }
    }

    private suspend fun prune(now: Long) {
        episodeDao.deleteOlderThan(now - 90L * 86_400_000L)
        factDao.pruneWeak(0.2, now - 30L * 86_400_000L)
    }

    /**
     * Track C4: per-tool success rates. Reports them and, for any tool with
     * >=5 attempts and <40% success, stores a memory fact so the loop stops
     * trusting it — the loop gets smarter without code changes.
     */
    private suspend fun toolStatsReport() {
        val dao = toolStatDao ?: return
        runCatching {
            dao.all().forEach { s ->
                val rate = if (s.attempts > 0) (s.successes * 100) / s.attempts else 0
                AppDebugServer.log(
                    "MEMORY", "Tool ${s.tool}: $rate% success (${s.successes}/${s.attempts})",
                )
                if (s.attempts >= 5 && rate < 40) {
                    memoryStore.remember(
                        "tool:${s.tool}", "unreliable ($rate% success)", "inferred",
                    )
                }
            }
        }.onFailure { e -> AppDebugServer.log("ERROR", "toolStats failed: ${e.message}") }
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        if (s.isEmpty()) return 0.0
        val mid = s.size / 2
        return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
    }

    private fun hourOf(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)
}