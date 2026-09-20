package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

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
         * 100k context limit (approx 100,000 characters).
         * Below this threshold, 100% of conversation turns are passed verbatim.
         * Once reached, older turns are automatically compacted into a durable digest.
         */
        const val MAX_CONTEXT_CHARS = 100_000
        const val RECENT_PRESERVE_CHARS = 40_000
        const val FACT_COMPACTED_KEY = "chat_compacted_summary"
    }

    suspend fun addUser(text: String) = add("user", text)

    suspend fun addShiina(text: String) = add("shiina", text)

    private suspend fun add(role: String, text: String) {
        // No truncation, no deletion! Store verbatim and permanent.
        dao.insert(ChatTurn(role = role, text = text, timestampMillis = System.currentTimeMillis()))
    }

    /** All turns in chronological order. */
    suspend fun allTurns(): List<ChatTurn> = dao.all()

    /**
     * Builds conversation history formatted for the prompt.
     * Returns all turns verbatim if within 100k context.
     * Auto-compacts older turns if 100k context is exceeded.
     */
    suspend fun buildConversationContext(): String {
        val turns = dao.all()
        if (turns.isEmpty()) return ""

        val totalChars = turns.sumOf { it.text.length + it.role.length + 4 }
        val savedDigest = runCatching { factDao?.get(FACT_COMPACTED_KEY)?.value }.getOrNull().orEmpty()

        if (totalChars <= MAX_CONTEXT_CHARS) {
            val verbatim = turns.joinToString("\n") {
                "${it.role.replaceFirstChar { c -> c.uppercase() }}: ${it.text}"
            }
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
            val turnLen = turn.text.length + turn.role.length + 4
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

        val recentVerbatim = recentTurns.joinToString("\n") {
            "${it.role.replaceFirstChar { c -> c.uppercase() }}: ${it.text}"
        }

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
        val topics = userMessages.map { it.text.trim() }
            .filter { it.length in 5..120 }
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