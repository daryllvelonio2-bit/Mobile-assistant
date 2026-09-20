package com.shiina.mobile.memory

import android.content.Context
import com.shiina.mobile.data.db.ChatTurnDao
import com.shiina.mobile.data.security.KeyStoreKeys
import com.shiina.mobile.debug.AppDebugServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asynchronous Memory Consolidation.
 * Runs in the background when conversation pauses or the app moves to background.
 * Extracts durable user preferences, facts, and rules from recent chat turns
 * and persists them to learned_memory.md and MemoryStore without adding latency
 * to live conversation turns.
 */
class MemoryConsolidator(
    private val context: Context,
    private val chatTurnDao: ChatTurnDao,
    private val learnedMemoryManager: LearnedMemoryManager,
    private val memoryStore: MemoryStore,
    private val keyStore: KeyStoreKeys,
    private val http: OkHttpClient,
    private val model: String = "gemini-3.5-flash-lite",
) {
    private val mutex = Mutex()
    private val prefs by lazy {
        context.getSharedPreferences("memory_consolidation", Context.MODE_PRIVATE)
    }

    suspend fun consolidate(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val lastExtracted = prefs.getLong(KEY_LAST_EXTRACTED_TIMESTAMP, 0L)
            val allTurns = runCatching { chatTurnDao.all() }.getOrDefault(emptyList())
            val newTurns = allTurns.filter {
                it.timestampMillis > lastExtracted &&
                    !it.text.contains("\"candidates\"") &&
                    !it.text.contains("\"finishReason\"") &&
                    !it.text.contains("MALFORMED_RESPONSE")
            }

            val userTurns = newTurns.filter { it.role.equals("user", ignoreCase = true) }
            if (userTurns.isEmpty()) {
                return@withLock 0
            }

            // Need at least 2 turns (one user, one assistant) or force flag
            if (newTurns.size < 2 && !force) {
                return@withLock 0
            }

            val key = keyStore.getKeys("gemini").firstOrNull()
            if (key.isNullOrBlank()) {
                AppDebugServer.log("MEMORY_CONSOLIDATION", "No Gemini key available for consolidation")
                return@withLock 0
            }

            val conversationText = newTurns.joinToString("\n") { turn ->
                "${turn.role.uppercase()}: ${turn.text}"
            }
            val existingMemory = runCatching { learnedMemoryManager.readMemory() }.getOrDefault("")

            val prompt = buildString {
                appendLine("You are an autonomous memory consolidation engine for a personal mobile companion.")
                appendLine("Analyze the recent conversation below between the user and Shiina.")
                appendLine("Extract any NEW durable user preferences, personal facts, rules, or habits that are worth remembering long-term.")
                appendLine()
                appendLine("GUIDELINES:")
                appendLine("- Extract durable facts: user's name, hobbies, likes/dislikes, goals, habits, relationships, lifestyle, rules.")
                appendLine("- Do NOT extract transient states (e.g. 'user is sleepy now', 'user is bored').")
                appendLine("- Do NOT re-extract facts that are already in Existing Memory.")
                appendLine("- If no new lasting facts or preferences were revealed, return an empty array: []")
                appendLine()
                if (existingMemory.isNotBlank()) {
                    appendLine("## Existing Memory (Already Known - Do Not Duplicate):")
                    appendLine(existingMemory.trim())
                    appendLine()
                }
                appendLine("## Recent Conversation:")
                appendLine(conversationText)
                appendLine()
                appendLine("Return JSON ONLY (no markdown fences, no explanatory text) in this exact schema:")
                appendLine("[")
                appendLine("  {\"key\": \"<concise_snake_case_key>\", \"value\": \"<concise fact or preference>\"}")
                appendLine("]")
            }

            val rawResponse = runCatching { callGemini(prompt, key) }.getOrElse { e ->
                AppDebugServer.log("ERROR", "Memory consolidation API call failed: ${e.message}")
                return@withLock 0
            }

            val extractedFacts = parseExtractedFacts(rawResponse)
            var count = 0
            for ((factKey, factValue) in extractedFacts) {
                learnedMemoryManager.learn(factKey, factValue, "background_reflection")
                runCatching { memoryStore.remember(factKey, factValue, "inferred") }
                AppDebugServer.log("MEMORY_CONSOLIDATION", "Consolidated: $factKey = $factValue")
                count++
            }

            val maxTimestamp = newTurns.maxOf { it.timestampMillis }
            prefs.edit().putLong(KEY_LAST_EXTRACTED_TIMESTAMP, maxTimestamp).apply()
            AppDebugServer.log("MEMORY_CONSOLIDATION", "Consolidation complete: $count new facts extracted from ${newTurns.size} turns")
            count
        }
    }

    private fun parseExtractedFacts(raw: String): List<Pair<String, String>> {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            if (text.startsWith("json", ignoreCase = true)) text = text.removeRange(0, 4)
            val end = text.lastIndexOf("```")
            if (end >= 0) text = text.substring(0, end)
            text = text.trim()
        }
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        text = text.substring(start, end + 1)

        return runCatching {
            val array = JSONArray(text)
            val results = mutableListOf<Pair<String, String>>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val k = obj.optString("key", "").trim().lowercase().replace(" ", "_")
                val v = obj.optString("value", "").trim()
                if (k.isNotBlank() && v.isNotBlank()) {
                    results.add(k to v)
                }
            }
            results
        }.getOrDefault(emptyList())
    }

    private fun callGemini(prompt: String, key: String): String {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("gemini error ${response.code}")
            }
            val respStr = response.body?.string().orEmpty()
            val json = JSONObject(respStr)
            val candidates = json.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            if (parts.length() == 0) return ""
            return parts.optJSONObject(0)?.optString("text", "").orEmpty()
        }
    }

    companion object {
        private const val KEY_LAST_EXTRACTED_TIMESTAMP = "last_extracted_turn_timestamp"
    }
}
