package com.shiina.mobile.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Phase 3 — one captured personal note: an idea, task, decision, reflection or
 * explicit note the user voiced during chat. Structured, categorized, on-device.
 *
 * v1 capture is explicit-tool driven (`CAPTURE_NOTE`), never a background NLP
 * extractor, so every row is something the user actually said.
 */
@Entity(
    tableName = "knowledge_notes",
    indices = [
        Index("createdMillis"),
        Index("kind"),
        Index("topic"),
    ],
)
data class KnowledgeNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val topic: String,
    val text: String,
    val source: String,
    val createdMillis: Long,
) {
    companion object {
        /** Canonical kinds. Anything the model emits is folded into one of these. */
        val KINDS = listOf("idea", "task", "decision", "reflection", "note")

        const val DEFAULT_KIND = "note"
        const val DEFAULT_TOPIC = "general"

        /** Max characters of a note carried into the live prompt. */
        private const val PROMPT_TEXT_CHARS = 140

        /**
         * Fold the model's free-form `kind` into one canonical value. Synonyms
         * map to a kind; unknown/blank input falls back to [DEFAULT_KIND].
         */
        fun normalizeKind(raw: String): String {
            return when (raw.trim().lowercase()) {
                "idea", "ideas", "thought", "thoughts", "suggestion", "suggestions",
                "concept", "concepts", "shower thought", "brainstorm" -> "idea"
                "task", "tasks", "todo", "to-do", "to do", "action", "actions",
                "job", "jobs", "errand", "errands", "goal" -> "task"
                "decision", "decisions", "decided", "choice", "choices", "call" -> "decision"
                "reflection", "reflections", "reflect", "journal", "diary", "note to self" -> "reflection"
                "note", "notes", "misc", "miscellaneous", "other", "info", "information" -> "note"
                else -> DEFAULT_KIND
            }
        }

        /**
         * Derive a short topic. An explicit topic wins; otherwise the first few
         * meaningful words of the text become the topic so notes still group.
         */
        fun normalizeTopic(rawTopic: String, text: String): String {
            val explicit = rawTopic.trim().replace(Regex("\\s+"), " ")
            if (explicit.isNotBlank()) return explicit.take(60)

            val cleaned = text
                .replace(Regex("(?i)^\\s*(note this|note that|remember|note)\\s*[:,\\-]?\\s*"), "")
                .lowercase()
                .replace(Regex("[^a-z0-9\\s-]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.isBlank()) return DEFAULT_TOPIC

            val words = cleaned.split(' ')
                .filter { it.isNotBlank() && it !in STOPWORDS }
                .take(4)
            if (words.isEmpty()) return DEFAULT_TOPIC
            return words.joinToString(" ").take(48)
        }

        /** One compact line per note for the live prompt. Newlines collapse. */
        fun promptLine(note: KnowledgeNote): String {
            val body = note.text.replace(Regex("\\s+"), " ").trim().take(PROMPT_TEXT_CHARS)
            return "- [${note.kind} · ${note.topic}] $body"
        }

        /**
         * Recent notes + the topic index, injected into live context so recall
         * ("what ideas did I mention?") works without a tool call. Empty string
         * when there is nothing to recall.
         */
        fun buildPromptBlock(notes: List<KnowledgeNote>, topics: List<String>): String {
            if (notes.isEmpty() && topics.isEmpty()) return ""
            val sb = StringBuilder()
            sb.appendLine("# Journal — notes you captured for them")
            if (notes.isEmpty()) {
                sb.appendLine("- (no notes captured yet)")
            } else {
                notes.forEach { sb.appendLine(promptLine(it)) }
            }
            if (topics.isNotEmpty()) {
                sb.appendLine("Topics on file: ${topics.joinToString(", ").take(300)}")
            }
            return sb.toString().trimEnd()
        }

        /** Human-readable rendering for the LIST_NOTES / SEARCH_NOTES tool receipts. */
        fun renderNotes(notes: List<KnowledgeNote>): String {
            if (notes.isEmpty()) return "No notes match."
            return notes.joinToString("\n") { n ->
                "#${n.id} [${n.kind} · ${n.topic}] ${n.text.replace(Regex("\\s+"), " ").trim().take(200)}"
            }
        }

        private val STOPWORDS = setOf(
            "a", "an", "the", "this", "that", "these", "those", "i", "im", "i'm", "me", "my",
            "we", "our", "you", "your", "to", "of", "in", "on", "at", "for", "with", "and",
            "or", "but", "is", "are", "was", "were", "be", "been", "want", "wanna", "need",
            "should", "would", "like", "gonna", "will", "next", "about", "it", "so", "just",
        )

        // ---- Query shapes (kept as constants so tests can pin them) ----
        const val SQL_ALL = "SELECT * FROM knowledge_notes ORDER BY createdMillis DESC, id DESC"
        const val SQL_RECENT = SQL_ALL + " LIMIT :limit"
        const val SQL_BY_KIND =
            "SELECT * FROM knowledge_notes WHERE kind = :kind ORDER BY createdMillis DESC LIMIT :limit"
        const val SQL_BY_TOPIC =
            "SELECT * FROM knowledge_notes WHERE topic = :topic ORDER BY createdMillis DESC LIMIT :limit"
        const val SQL_SEARCH =
            "SELECT * FROM knowledge_notes WHERE text LIKE '%' || :query || '%' " +
                "OR topic LIKE '%' || :query || '%' OR kind LIKE '%' || :query || '%' " +
                "ORDER BY createdMillis DESC LIMIT :limit"
        const val SQL_TOPICS = "SELECT DISTINCT topic FROM knowledge_notes ORDER BY topic ASC"
        const val SQL_COUNT = "SELECT COUNT(*) FROM knowledge_notes"
        const val SQL_DELETE_ID = "DELETE FROM knowledge_notes WHERE id = :id"
        const val SQL_DELETE_ALL = "DELETE FROM knowledge_notes"
    }
}

@Dao
interface KnowledgeNoteDao {
    @Insert
    suspend fun insert(note: KnowledgeNote): Long

    @Query(KnowledgeNote.SQL_ALL)
    suspend fun all(): List<KnowledgeNote>

    @Query(KnowledgeNote.SQL_RECENT)
    suspend fun recent(limit: Int): List<KnowledgeNote>

    @Query(KnowledgeNote.SQL_BY_KIND)
    suspend fun byKind(kind: String, limit: Int): List<KnowledgeNote>

    @Query(KnowledgeNote.SQL_BY_TOPIC)
    suspend fun byTopic(topic: String, limit: Int): List<KnowledgeNote>

    @Query(KnowledgeNote.SQL_SEARCH)
    suspend fun search(query: String, limit: Int): List<KnowledgeNote>

    @Query(KnowledgeNote.SQL_TOPICS)
    suspend fun topics(): List<String>

    @Query(KnowledgeNote.SQL_COUNT)
    suspend fun count(): Int

    @Query(KnowledgeNote.SQL_DELETE_ID)
    suspend fun delete(id: Long)

    @Query(KnowledgeNote.SQL_DELETE_ALL)
    suspend fun deleteAll()
}
