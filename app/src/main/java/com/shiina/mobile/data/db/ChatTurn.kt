package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One Talk exchange turn (Phase 6 chat continuity).
 * Stored permanently in full fidelity without arbitrary limits.
 */
@Entity(tableName = "chat_turns")
data class ChatTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val timestampMillis: Long,
)

@Dao
interface ChatTurnDao {
    @Insert
    suspend fun insert(turn: ChatTurn)

    @Query("SELECT * FROM chat_turns ORDER BY id ASC")
    suspend fun all(): List<ChatTurn>

    @Query("SELECT * FROM chat_turns ORDER BY id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<ChatTurn>

    /** Live stream of the newest turns (descending; reverse for chronological UI). */
    @Query("SELECT * FROM chat_turns ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ChatTurn>>

    @Query("SELECT COUNT(*) FROM chat_turns WHERE timestampMillis >= :startMillis AND timestampMillis < :endMillis")
    suspend fun countInRange(startMillis: Long, endMillis: Long): Int

    @Query("SELECT COUNT(*) FROM chat_turns")
    suspend fun count(): Int

    @Query("DELETE FROM chat_turns WHERE id NOT IN (SELECT id FROM chat_turns ORDER BY id DESC LIMIT :keep)")
    suspend fun keepLatest(keep: Int)

    @Query("DELETE FROM chat_turns")
    suspend fun deleteAll()
}

/**
 * Uncapped talk thread continuity with 100k context auto-compaction.
 * Preserves all conversation turns verbatim in SQLite without arbitrary limits.
 * Below 100k context, all turns are passed verbatim to the model.
 * When conversation context approaches the 100k limit, older turns are
 * automatically compacted into a durable conversation summary while recent
 * turns remain in full verbatim fidelity.
 */
class ChatHistory(
    private val dao: ChatTurnDao,
    private val factDao: MemoryFactDao? = null,
) {
    companion object {
        /**
         * Sliding prompt context window:
         * All turns remain stored verbatim in SQLite Room DB permanently.
         * To keep prompt tokens, latency, and radio/battery usage minimal,
         * active conversation keeps ~15-20 recent turns verbatim, and older
         * turns are distilled into the compacted summary digest.
         */
        const val MAX_CONTEXT_CHARS = 12_000
        const val RECENT_PRESERVE_CHARS = 6_000
        const val FACT_COMPACTED_KEY = "chat_compacted_summary"

        private val TURN_DATE_FMT = java.text.SimpleDateFormat("yyyy-MM-dd h:mm a", java.util.Locale.getDefault())

        fun formatTurn(turn: ChatTurn): String {
            val timePrefix = if (turn.timestampMillis > 0L) {
                "[${TURN_DATE_FMT.format(java.util.Date(turn.timestampMillis))}] "
            } else ""
            return "$timePrefix${turn.role.replaceFirstChar { c -> c.uppercase() }}: ${turn.text}"
        }

        fun formatDuration(diffMillis: Long): String {
            val totalMinutes = (diffMillis / 60_000L).coerceAtLeast(1L)
            val hours = totalMinutes / 60L
            val minutes = totalMinutes % 60L
            return when {
                hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
                hours > 0L -> "${hours} hour" + (if (hours > 1) "s" else "")
                else -> "${minutes} minute" + (if (minutes > 1) "s" else "")
            }
        }

        fun formatTurnsWithSessionBreaks(turns: List<ChatTurn>): String = buildString {
            for (i in turns.indices) {
                val curr = turns[i]
                if (i > 0) {
                    val prev = turns[i - 1]
                    val gap = curr.timestampMillis - prev.timestampMillis
                    if (gap >= 30 * 60 * 1000L) { // 30+ minutes gap indicates a session break
                        appendLine()
                        appendLine("--- [Session Break: ${formatDuration(gap)} later] ---")
                    }
                }
                appendLine(formatTurn(curr))
            }
        }.trimEnd()
    }

    suspend fun addUser(text: String) = add("user", text)

    suspend fun addShiina(text: String) {
        if (text.contains("\"candidates\"") || text.contains("\"finishReason\"") || text.contains("MALFORMED_RESPONSE")) {
            return
        }
        add("shiina", text)
    }

    private suspend fun add(role: String, text: String) {
        // No truncation, no deletion! Store verbatim and permanent.
        dao.insert(ChatTurn(role = role, text = text, timestampMillis = System.currentTimeMillis()))
    }

    /** All turns in chronological order. */
    suspend fun allTurns(): List<ChatTurn> = dao.all()

    /**
     * Timestamp of the most recent turn in the database, or 0L if empty.
     */
    suspend fun lastTurnTimestamp(): Long {
        return dao.latest(1).firstOrNull()?.timestampMillis ?: 0L
    }

    /**
     * Timestamp of the most recent interaction BEFORE the current userText was received,
     * or 0L if no previous turns exist.
     */
    suspend fun getPreviousTurnTimestamp(currentUserText: String? = null): Long {
        val latest = dao.latest(2)
        if (latest.isEmpty()) return 0L
        if (currentUserText != null && latest[0].text == currentUserText && latest[0].role.equals("user", ignoreCase = true)) {
            return if (latest.size > 1) latest[1].timestampMillis else 0L
        }
        return latest[0].timestampMillis
    }

    /**
     * Builds conversation history formatted for the prompt.
     * Returns all turns verbatim if within 100k context.
     * Auto-compacts older turns if 100k context is exceeded.
     */
    suspend fun buildConversationContext(): String {
        val rawTurns = dao.all()
        if (rawTurns.isEmpty()) return ""

        // Filter out any corrupted error/JSON turns
        val turns = rawTurns.filterNot {
            it.text.contains("\"candidates\"") || it.text.contains("\"finishReason\"") || it.text.contains("MALFORMED_RESPONSE")
        }
        if (turns.isEmpty()) return ""

        val totalChars = turns.sumOf { it.text.length + it.role.length + 24 }
        val savedDigest = runCatching { factDao?.get(FACT_COMPACTED_KEY)?.value }.getOrNull().orEmpty()

        if (totalChars <= MAX_CONTEXT_CHARS) {
            val verbatim = formatTurnsWithSessionBreaks(turns)
            return if (savedDigest.isNotBlank()) {
                "## Earlier Conversation (Compacted)\n$savedDigest\n\n## Active Conversation\n$verbatim"
            } else {
                verbatim
            }
        }

        // Exceeded 100k context: Auto-compact older turns!
        return autoCompactAndBuild(turns, savedDigest)
    }

    private suspend fun autoCompactAndBuild(turns: List<ChatTurn>, existingDigest: String): String {
        // Split into older turns to compact and recent turns to preserve verbatim
        var recentChars = 0
        val recentTurns = mutableListOf<ChatTurn>()
        val olderTurns = mutableListOf<ChatTurn>()

        for (turn in turns.reversed()) {
            val turnLen = turn.text.length + turn.role.length + 24
            if (recentChars + turnLen <= RECENT_PRESERVE_CHARS || recentTurns.isEmpty()) {
                recentTurns.add(turn)
                recentChars += turnLen
            } else {
                olderTurns.add(turn)
            }
        }
        olderTurns.reverse()
        recentTurns.reverse()

        // Generate compacted digest from older turns
        val newDigest = distillTurns(olderTurns, existingDigest)

        // Persist the compacted digest
        if (newDigest.isNotBlank()) {
            runCatching {
                factDao?.upsert(
                    MemoryFact(
                        key = FACT_COMPACTED_KEY,
                        value = newDigest.take(4000),
                        confidence = 1.0,
                        source = "auto_compact",
                        updatedMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }

        val recentVerbatim = formatTurnsWithSessionBreaks(recentTurns)

        return buildString {
            if (newDigest.isNotBlank()) {
                appendLine("## Earlier Conversation (Compacted from 100k+ context)")
                appendLine(newDigest)
                appendLine()
                appendLine("## Active Conversation")
            }
            append(recentVerbatim)
        }
    }

    private fun distillTurns(turns: List<ChatTurn>, existingDigest: String): String {
        if (turns.isEmpty()) return existingDigest
        val sb = StringBuilder()
        if (existingDigest.isNotBlank()) {
            sb.append(existingDigest.trim()).append(" ")
        }
        val userMessages = turns.filter { it.role.equals("user", ignoreCase = true) }
        val topics = userMessages.map {
            val time = if (it.timestampMillis > 0L) "[${TURN_DATE_FMT.format(java.util.Date(it.timestampMillis))}] " else ""
            "$time${it.text.trim()}"
        }.filter { it.length in 5..140 }
            .takeLast(20)

        sb.append("Previously discussed: ")
        sb.append(topics.joinToString("; ").take(1500))
        sb.append(".")
        return sb.toString().trim()
    }

    /** Backward compatibility: recent turns if requested by count. */
    suspend fun recentTurns(limit: Int? = null): List<ChatTurn> {
        return if (limit != null && limit > 0) {
            dao.latest(limit).reversed()
        } else {
            dao.all()
        }
    }
}