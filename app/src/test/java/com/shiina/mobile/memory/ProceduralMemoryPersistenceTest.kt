package com.shiina.mobile.memory

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for AUDIT finding #4: procedural-memory usage counters must be persisted.
 *
 * Each test builds the counters in one store instance, then constructs a *fresh* store over the
 * same storage directory to simulate a process restart (which is exactly what previously reset
 * usage_count/last_used back to the last persisted values).
 */
class ProceduralMemoryPersistenceTest {

    private fun newStore(dir: File) = ProceduralMemoryStore(context = null, baseDir = dir)

    private fun readPersistedUsageCount(dir: File, id: String): Int {
        val array = JSONArray(File(dir, "learned_procedures.json").readText())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.getString("id") == id) return obj.getInt("usage_count")
        }
        throw AssertionError("procedure $id not present in persisted JSON")
    }

    @Test
    fun getProcedureDetails_persistsUsageCountAcrossRestart() = runBlocking {
        val dir = Files.createTempDirectory("proc-mem-getdetails").toFile()
        val store = newStore(dir)
        val before = store.getProcedure("proc_clock_alarm")!!.usageCount

        // Two GET_PROCEDURE calls in this process.
        store.getProcedureDetails("proc_clock_alarm")
        store.getProcedureDetails("proc_clock_alarm")

        // Restart: new instance, same directory.
        val restarted = newStore(dir)
        val after = restarted.getProcedure("proc_clock_alarm")!!.usageCount

        assertEquals(before + 2, after)
        assertEquals(before + 2, readPersistedUsageCount(dir, "proc_clock_alarm"))
    }

    @Test
    fun recordUsage_persistsUsageCountAcrossRestart() = runBlocking {
        val dir = Files.createTempDirectory("proc-mem-recordusage").toFile()
        val store = newStore(dir)
        val before = store.getProcedure("proc_clock_alarm")!!.usageCount

        assertTrue(store.recordUsage("proc_clock_alarm"))

        val restarted = newStore(dir)
        assertEquals(before + 1, restarted.getProcedure("proc_clock_alarm")!!.usageCount)
        assertEquals(before + 1, readPersistedUsageCount(dir, "proc_clock_alarm"))
    }

    @Test
    fun recordUsage_unknownProcedure_returnsFalseAndDoesNotThrow() = runBlocking {
        val dir = Files.createTempDirectory("proc-mem-unknown").toFile()
        val store = newStore(dir)
        assertFalse(store.recordUsage("does_not_exist_at_all"))
    }

    @Test
    fun getProcedureDetails_unknownProcedure_returnsNotFoundAndPersistsNothingNew() = runBlocking {
        val dir = Files.createTempDirectory("proc-mem-missing").toFile()
        val store = newStore(dir)
        val receipt = store.getProcedureDetails("totally_unknown_key")
        assertTrue(receipt.contains("not found"))
    }
}
