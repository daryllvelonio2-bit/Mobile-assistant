package com.shiina.mobile.memory

import android.content.Context
import com.shiina.mobile.debug.AppDebugServer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persistent file-backed memory manager.
 * Stores durable facts, user preferences, and AI-discovered insights in `learned_memory.md`
 * on disk, and feeds them into the prompt under `# Learned Memory`.
 * Also syncs with MemoryStore so SQLite facts remain consistent.
 */
class LearnedMemoryManager(
    private val context: Context,
    private val memoryStore: MemoryStore? = null,
) {
    private val mutex = Mutex()
    private val memoryFile by lazy { File(context.filesDir, "learned_memory.md") }
    private val memoryEntries = LinkedHashMap<String, MemoryEntry>()

    data class MemoryEntry(
        val key: String,
        val value: String,
        val timestamp: Long = System.currentTimeMillis(),
        val source: String = "ai_learned",
    )

    init {
        loadFile()
    }

    private fun loadFile() {
        if (!memoryFile.exists()) return
        runCatching {
            memoryEntries.clear()
            val lines = memoryFile.readLines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("- **") && trimmed.contains("**:")) {
                    val key = trimmed.substringAfter("- **").substringBefore("**:")
                    val afterColon = trimmed.substringAfter("**:").trim()
                    // Strip optional (updated: ...) suffix if present
                    val value = if (afterColon.contains("(updated:")) {
                        afterColon.substringBeforeLast("(updated:").trim()
                    } else {
                        afterColon
                    }
                    if (key.isNotBlank() && value.isNotBlank()) {
                        memoryEntries[key.lowercase()] = MemoryEntry(key, value)
                    }
                }
            }
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "Failed to load learned_memory.md: ${e.message}")
        }
    }

    /**
     * Get a learned fact value by key synchronously from in-memory cache.
     */
    @Synchronized
    fun get(key: String): String? {
        val k = key.trim().lowercase().replace(" ", "_")
        return memoryEntries[k]?.value
    }

    /**
     * Persist a learned fact to the file and memory store.
     */
    suspend fun learn(key: String, value: String, source: String = "ai_learned"): String = withContext(Dispatchers.IO) {
        val k = key.trim().lowercase().replace(" ", "_")
        val v = value.trim()
        if (k.isEmpty() || v.isEmpty()) return@withContext "learn requires non-empty key and value"

        mutex.withLock {
            memoryEntries[k] = MemoryEntry(k, v, System.currentTimeMillis(), source)
            saveFileLocked()
        }

        // Also sync to MemoryStore
        runCatching {
            memoryStore?.remember(k, v, source)
        }

        AppDebugServer.log("LEARNED_MEMORY", "Learned: $k = $v ($source)")
        "Learned and saved to memory file: $k = $v"
    }

    /**
     * Read the full learned memory file formatted for the prompt.
     */
    suspend fun readMemory(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (memoryEntries.isEmpty()) {
                if (memoryFile.exists()) {
                    loadFile()
                }
                if (memoryEntries.isEmpty() && memoryStore != null) {
                    val facts = runCatching { memoryStore.allFacts() }.getOrDefault(emptyList())
                    for (fact in facts) {
                        if (fact.confidence >= 0.5) {
                            memoryEntries[fact.key.lowercase()] = MemoryEntry(fact.key, fact.value, fact.updatedMillis, fact.source)
                        }
                    }
                    if (memoryEntries.isNotEmpty()) {
                        saveFileLocked()
                    }
                }
            }

            if (memoryEntries.isEmpty()) return@withLock ""

            buildString {
                for ((_, entry) in memoryEntries) {
                    appendLine("- **${entry.key}**: ${entry.value}")
                }
            }.trim()
        }
    }

    /**
     * Forget/delete an entry by key.
     */
    suspend fun forget(key: String): Boolean = withContext(Dispatchers.IO) {
        val k = key.trim().lowercase().replace(" ", "_")
        val removed = mutex.withLock {
            val existed = memoryEntries.remove(k) != null
            if (existed) saveFileLocked()
            existed
        }
        runCatching { memoryStore?.forgetKey(k) }
        removed
    }

    /**
     * Wipe all learned memory.
     */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            memoryEntries.clear()
            if (memoryFile.exists()) memoryFile.delete()
        }
    }

    private fun saveFileLocked() {
        runCatching {
            val sb = StringBuilder()
            sb.appendLine("# Persistent Learned Memory")
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            for ((_, entry) in memoryEntries) {
                sb.appendLine("- **${entry.key}**: ${entry.value} (updated: ${fmt.format(Date(entry.timestamp))})")
            }
            memoryFile.writeText(sb.toString())
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "Failed to save learned_memory.md: ${e.message}")
        }
    }
}
