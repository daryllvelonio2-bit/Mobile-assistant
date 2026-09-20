package com.shiina.mobile.memory

import com.shiina.mobile.data.db.ChatTurnDao
import com.shiina.mobile.data.db.MemoryEpisodeDao
import com.shiina.mobile.data.db.MemoryFact
import com.shiina.mobile.data.db.MemoryFactDao
import com.shiina.mobile.data.db.MemorySummaryDao
import com.shiina.mobile.debug.AppDebugServer

/**
 * Phase 3 semantic facts: store, corroborate, contradict, cap, forget.
 * Explicit "remember key=value" from Talk needs no API call; decisions can
 * store facts via the LEARN_FACT verb; the nightly pass infers facts.
 * Learning rule: the user's stated word ALWAYS wins over inference, and a
 * contradiction crushes confidence instead of deleting — wrong lessons fade,
 * they are not pretended away.
 * Audit M9: keys normalized (lowercase, underscores, alias map) so
 * "Bed Time", "bed-time" and "bedtime" land on one fact.
 * Audit M14: statusLine breaks facts down by source.
 */
class MemoryStore(
    private val factDao: MemoryFactDao,
    private val episodeDao: MemoryEpisodeDao,
    private val chatDao: ChatTurnDao,
    private val summaryDao: MemorySummaryDao,
) {

    /** Insert or corroborate a fact. Stated facts start at 1.0 and always override inference. */
    suspend fun remember(key: String, value: String, source: String) {
        val k = normalizeKey(key)
        val v = value.trim().take(200)
        if (k.isEmpty() || v.isEmpty()) return
        val existing = factDao.get(k)
        val confidence = if (existing == null) {
            if (source == "stated") 1.0 else 0.5
        } else if (source == "stated") {
            1.0
        } else if (existing.source == "stated") {
            existing.confidence
        } else {
            (existing.confidence + 0.1).coerceAtMost(1.0)
        }
        factDao.upsert(MemoryFact(k, v, confidence, source, System.currentTimeMillis()))
        val excess = factDao.count() - MAX_FACTS
        if (excess > 0) factDao.evictLowest(excess)
        AppDebugServer.log("MEMORY", "Fact stored: $k=$v (conf=$confidence, $source)")
    }

    /**
     * Correction learning: the user pushed back on [key] ("don't call me
     * that", "wrong"). Crush confidence to 30% instead of deleting — a
     * contradicted lesson fades but stays inspectable. Returns false when
     * no such fact exists.
     */
    suspend fun contradict(key: String): Boolean {
        val k = normalizeKey(key)
        val existing = factDao.get(k) ?: return false
        val crushed = (existing.confidence * 0.3).coerceAtLeast(0.05)
        factDao.upsert(existing.copy(confidence = crushed, updatedMillis = System.currentTimeMillis()))
        AppDebugServer.log("MEMORY", "Fact contradicted: $k (${existing.confidence} -> $crushed)")
        return true
    }

    /** Delete one fact by key. Returns false when nothing was stored. */
    suspend fun forgetKey(key: String): Boolean {
        val k = normalizeKey(key)
        if (factDao.get(k) == null) return false
        factDao.delete(k)
        AppDebugServer.log("MEMORY", "Fact forgotten: $k")
        return true
    }

    /** Explicit Talk command ("remember bedtime=11pm") — parsed locally, no API. */
    suspend fun rememberCommand(text: String): String {
        val (k, v) = if (text.contains("=")) {
            text.substringBefore("=").trim() to text.substringAfter("=").trim()
        } else {
            text.substringBefore(" ").trim() to text.substringAfter(" ", "").trim()
        }
        if (k.isEmpty() || v.isEmpty()) {
            return "Tell me as key=value, like: remember bedtime=11pm"
        }
        remember(k, v, "stated")
        return "Remembered: ${normalizeKey(k)} = $v"
    }

    suspend fun allFacts(): List<MemoryFact> = factDao.all()

    /** Single fact value lookup (identity grounding for the Talk prompt). */
    suspend fun factValue(key: String): String? =
        runCatching { factDao.get(normalizeKey(key))?.value }.getOrNull()

    suspend fun deleteFact(key: String) = factDao.delete(key)

    suspend fun updateFact(key: String, value: String) {
        val fact = factDao.get(key) ?: return
        factDao.upsert(fact.copy(value = value.take(200), updatedMillis = System.currentTimeMillis()))
    }

    /** "Forget everything": facts, episodes, chat history, digests — all wiped. */
    suspend fun forgetAll() {
        factDao.deleteAll()
        episodeDao.deleteAll()
        chatDao.deleteAll()
        summaryDao.deleteAll()
        AppDebugServer.log("MEMORY", "forgetAll: wiped facts, episodes, chat turns, digests")
    }

    /** Status line for the Settings memory viewer (audit M14: per-source counts). */
    suspend fun statusLine(): String {
        val facts = runCatching { factDao.all() }.getOrDefault(emptyList())
        val stated = facts.count { it.source == "stated" }
        val inferred = facts.size - stated
        val e = runCatching { episodeDao.count() }.getOrDefault(0)
        val d = runCatching { summaryDao.count() }.getOrDefault(0)
        val days = runCatching { episodeDao.distinctDayCount() }.getOrDefault(0)
        return "${facts.size} facts ($stated stated, $inferred inferred) · " +
            "$e recent rounds ($days days) · $d weekly digests — on-device only."
    }

    suspend fun recentDigests(limit: Int = 3) =
        runCatching { summaryDao.recent(limit) }.getOrDefault(emptyList())

    /** M9: lowercase, collapse separators to underscores, apply alias map. */
    fun normalizeKey(raw: String): String {
        val k = raw.trim().lowercase()
            .replace(Regex("[\\s\\-]+"), "_")
            .replace(Regex("[^a-z0-9_:]"), "")
            .take(64)
        return KEY_ALIASES[k] ?: k
    }

    companion object {
        const val MAX_FACTS = 200
        private val KEY_ALIASES = mapOf(
            "bed_time" to "bedtime",
            "bedtime_" to "bedtime",
            "name" to "user_name",
            "my_name" to "user_name",
        )
    }
}