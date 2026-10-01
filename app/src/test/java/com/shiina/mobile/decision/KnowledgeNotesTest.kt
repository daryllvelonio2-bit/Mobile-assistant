package com.shiina.mobile.decision

import com.shiina.mobile.data.db.KnowledgeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 — knowledge capture & structured journaling.
 *
 * Pure JVM: kind folding, topic derivation, the prompt block that makes recall
 * work without a tool call, the tool-receipt rendering, and the SQL shapes the
 * DAO is pinned to.
 */
class KnowledgeNotesTest {

    private fun note(
        id: Long,
        kind: String,
        topic: String,
        text: String,
        created: Long = 1_700_000_000_000L,
        source: String = "chat",
    ) = KnowledgeNote(id = id, kind = kind, topic = topic, text = text, source = source, createdMillis = created)

    // ---- kind mapping ----

    @Test
    fun normalizeKind_foldsSynonymsOntoCanonicalKinds() {
        assertEquals("idea", KnowledgeNote.normalizeKind("idea"))
        assertEquals("idea", KnowledgeNote.normalizeKind("Thought"))
        assertEquals("idea", KnowledgeNote.normalizeKind("suggestions"))
        assertEquals("task", KnowledgeNote.normalizeKind("TODO"))
        assertEquals("task", KnowledgeNote.normalizeKind("to-do"))
        assertEquals("task", KnowledgeNote.normalizeKind("action"))
        assertEquals("decision", KnowledgeNote.normalizeKind("decided"))
        assertEquals("decision", KnowledgeNote.normalizeKind("choice"))
        assertEquals("reflection", KnowledgeNote.normalizeKind("journal"))
        assertEquals("reflection", KnowledgeNote.normalizeKind("Diary"))
        assertEquals("note", KnowledgeNote.normalizeKind("misc"))
    }

    @Test
    fun normalizeKind_fallsBackToNoteForBlankOrUnknown() {
        assertEquals("note", KnowledgeNote.normalizeKind(""))
        assertEquals("note", KnowledgeNote.normalizeKind("   "))
        assertEquals("note", KnowledgeNote.normalizeKind("banana"))
        // every canonical kind round-trips
        KnowledgeNote.KINDS.forEach { assertEquals(it, KnowledgeNote.normalizeKind(it)) }
    }

    // ---- topic derivation ----

    @Test
    fun normalizeTopic_explicitWinsAndIsCollapsed() {
        assertEquals("memory module", KnowledgeNote.normalizeTopic("  memory   module ", "whatever text"))
        assertEquals("memory module", KnowledgeNote.normalizeTopic("memory module", "whatever text"))
    }

    @Test
    fun normalizeTopic_derivesFromTextWhenAbsent() {
        val topic = KnowledgeNote.normalizeTopic(
            "",
            "note this: I want to refactor the memory module next week",
        )
        // "note this"/"I"/"want"/"to"/"the"/"next" are stripped as noise.
        assertEquals("refactor memory module week", topic)
    }

    @Test
    fun normalizeTopic_blankTextFallsBackToGeneral() {
        assertEquals("general", KnowledgeNote.normalizeTopic("", ""))
        assertEquals("general", KnowledgeNote.normalizeTopic("   ", "the and of"))
    }

    // ---- prompt block (recall without a tool call) ----

    @Test
    fun buildPromptBlock_includesRecentNotesAndTopics() {
        val notes = listOf(
            note(2, "idea", "memory module", "I want to refactor the memory module next week"),
            note(1, "task", "market", "buy milk on the way home"),
        )
        val block = KnowledgeNote.buildPromptBlock(notes, listOf("market", "memory module"))

        assertTrue("journal header", block.contains("# Journal"))
        assertTrue(block.contains("I want to refactor the memory module next week"))
        assertTrue(block.contains("buy milk on the way home"))
        assertTrue(block.contains("[idea · memory module]"))
        assertTrue(block.contains("[task · market]"))
        assertTrue(block.contains("Topics on file: market, memory module"))
    }

    @Test
    fun buildPromptBlock_isEmptyWithNoData() {
        assertEquals("", KnowledgeNote.buildPromptBlock(emptyList(), emptyList()))
    }

    @Test
    fun buildPromptBlock_topicsOnlyStillRenders() {
        val block = KnowledgeNote.buildPromptBlock(emptyList(), listOf("work"))
        assertTrue(block.contains("Topics on file: work"))
        assertTrue(block.contains("no notes captured yet"))
    }

    @Test
    fun promptLine_collapsesNewlinesAndCapsLength() {
        val messy = note(3, "note", "t", "line one\nline two\r\n   line three")
        val line = KnowledgeNote.promptLine(messy)
        assertFalse("newlines must not break the block", line.contains("\n"))
        assertTrue(line.contains("line one line two line three"))
    }

    // ---- tool receipts ----

    @Test
    fun renderNotes_listsIdKindTopicAndText() {
        val rendered = KnowledgeNote.renderNotes(listOf(note(7, "decision", "housing", "sign the lease")))
        assertTrue(rendered.contains("#7"))
        assertTrue(rendered.contains("[decision · housing]"))
        assertTrue(rendered.contains("sign the lease"))
    }

    @Test
    fun renderNotes_emptyIsClear() {
        assertEquals("No notes match.", KnowledgeNote.renderNotes(emptyList()))
    }

    // ---- DAO query shapes ----

    @Test
    fun daoQueryShapes_arePinned() {
        assertTrue(KnowledgeNote.SQL_ALL.contains("ORDER BY createdMillis DESC"))
        assertTrue(KnowledgeNote.SQL_RECENT.endsWith("LIMIT :limit"))
        assertTrue(KnowledgeNote.SQL_BY_KIND.contains("WHERE kind = :kind"))
        assertTrue(KnowledgeNote.SQL_BY_KIND.contains("LIMIT :limit"))
        assertTrue(KnowledgeNote.SQL_BY_TOPIC.contains("WHERE topic = :topic"))
        assertTrue(KnowledgeNote.SQL_TOPICS.contains("DISTINCT topic"))
        assertTrue(KnowledgeNote.SQL_TOPICS.contains("ORDER BY topic ASC"))
        assertTrue(KnowledgeNote.SQL_COUNT.contains("COUNT(*)"))
        assertTrue(KnowledgeNote.SQL_DELETE_ID.contains("WHERE id = :id"))
        // search must span the fields the user would remember: text, topic, kind
        assertTrue(KnowledgeNote.SQL_SEARCH.contains("text LIKE"))
        assertTrue(KnowledgeNote.SQL_SEARCH.contains("topic LIKE"))
        assertTrue(KnowledgeNote.SQL_SEARCH.contains("kind LIKE"))
    }

    // ---- schema mapping (entity <-> migration column names) ----

    @Test
    fun entityExposesTheColumnsTheMigrationCreates() {
        val fields = KnowledgeNote::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(fields.containsAll(listOf("id", "kind", "topic", "text", "source", "createdMillis")))
    }
}
