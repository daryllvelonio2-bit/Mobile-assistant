package com.shiina.mobile.memory

import android.content.Context
import com.shiina.mobile.data.db.MemoryFact
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * Dynamic Learning & Real-World Context Engine.
 *
 * Synthesizes:
 * 1. Real-World Scenario Detection: Evaluates live device telemetry, time of day,
 *    and user activity to generate actionable real-world situational guidance
 *    (late-night active, battery alerts, morning routines, screen fatigue).
 * 2. Categorized Lifelong Learning: Structures dynamic facts into distinct human domains
 *    (Identity, Circadian & Routine, Preferences, Health & Goals, Principles).
 */
class DynamicLearningEngine(
    private val context: Context? = null,
    private val memoryStore: MemoryStore? = null,
    private val learnedMemoryManager: LearnedMemoryManager? = null,
) {

    /**
     * Evaluates live device telemetry and temporal rhythms to identify active real-world scenarios.
     */
    fun evaluateRealWorldScenarios(
        sensesJson: String,
        hoursInactive: Double = 0.0,
    ): List<String> {
        val scenarios = mutableListOf<String>()
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val senses = runCatching { JSONObject(sensesJson) }.getOrNull()

        val batt = senses?.optInt("battery", -1) ?: -1
        val charging = senses?.optBoolean("charging", false) ?: false
        val fgApp = senses?.optString("foreground_app", "").orEmpty()
        val musicPlaying = senses?.optBoolean("music_playing", false) ?: false
        val currentMusic = senses?.optString("current_music")?.takeIf { it.isNotBlank() && it != "null" }
        val audioRoute = senses?.optString("audio_route", "").orEmpty()

        // 1. Late Night Active Rule (11:00 PM - 4:59 AM)
        if (hour in 23..24 || hour in 0..4) {
            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            scenarios.add(
                "REAL-WORLD SCENARIO [LATE NIGHT ACTIVE]: It is $timeFmt, user active" +
                    (if (fgApp.isNotBlank()) " using '$fgApp'" else "") +
                    ". Gently firm: it's late, push rest over screen time.",
            )
        } else if (hour in 5..9) {
            // 2. Early Morning Start
            val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            scenarios.add(
                "REAL-WORLD SCENARIO [MORNING START]: It is $timeFmt. Gentle greeting, ask about sleep and today's plan.",
            )
        } else if (hour in 12..14) {
            // 3. Midday Check-in
            scenarios.add(
                "REAL-WORLD SCENARIO [MIDDAY BREAK]: Midday window. Nudge lunch or a short break if natural.",
            )
        }

        // 4. Battery Alerts
        if (batt in 1..15 && !charging) {
            scenarios.add(
                "REAL-WORLD SCENARIO [CRITICAL BATTERY]: Battery at $batt%, not charging. Remind them to plug in soon.",
            )
        }

        // 5. Audio Route & Background Media Awareness
        if (musicPlaying && !currentMusic.isNullOrBlank()) {
            scenarios.add(
                "REAL-WORLD SCENARIO [MEDIA PLAYBACK ACTIVE]: Background media is actively playing ($currentMusic)" +
                    (if (audioRoute.contains("bluetooth", ignoreCase = true) || audioRoute.contains("headset", ignoreCase = true)) " via headphones" else "") +
                    ". Only reference if directly relevant to the user's intent.",
            )
        }

        // 6. User Return After Absence / Inactivity
        if (hoursInactive in 0.5..9.9) {
            val hStr = if (hoursInactive < 1.0) {
                "${(hoursInactive * 60).toInt()} minutes"
            } else if (hoursInactive < 1.5) {
                "about an hour"
            } else {
                "${"%.1f".format(hoursInactive)} hours"
            }
            scenarios.add(
                "REAL-WORLD SCENARIO [USER RETURN AFTER $hStr]: The user returned after an absence ($hStr). " +
                    "Greet them in the present moment matching the current time of day. Do not dwell on concluded topics from earlier hours.",
            )
        } else if (hoursInactive >= 10.0) {
            val h = hoursInactive.toInt()
            scenarios.add(
                "REAL-WORLD SCENARIO [NEGLECT / ABSENCE]: No interaction for $h hours. Pouty: tease them about abandoning you, then warm back up.",
            )
        }

        return scenarios
    }

    /**
     * Builds structured, categorized knowledge blocks from persistent memory facts and learned markdown.
     */
    suspend fun buildCategorizedKnowledge(
        rawMemoryFile: String,
        memoryFacts: List<MemoryFact>,
    ): String {
        if (rawMemoryFile.isBlank() && memoryFacts.isEmpty()) return ""

        val categorized = LinkedHashMap<String, MutableList<String>>()
        categorized["Identity & Background"] = mutableListOf()
        categorized["Circadian Rhythm & Daily Routine"] = mutableListOf()
        categorized["Personal Preferences & Tastes"] = mutableListOf()
        categorized["Health, Sleep & Well-being"] = mutableListOf()
        categorized["User Principles & Stated Rules"] = mutableListOf()
        categorized["General Knowledge & Insights"] = mutableListOf()

        // 1. Process structured memory facts
        for (fact in memoryFacts) {
            if (fact.confidence < 0.3) continue
            val cat = categorizeKey(fact.key)
            val line = "${fact.key.replace('_', ' ')}: ${fact.value}"
            categorized[cat]?.add(line)
        }

        // 2. Process file-based entries
        if (rawMemoryFile.isNotBlank()) {
            for (line in rawMemoryFile.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("- **") && trimmed.contains("**:")) {
                    val key = trimmed.substringAfter("- **").substringBefore("**:")
                    val value = trimmed.substringAfter("**:").trim()
                    val cat = categorizeKey(key)
                    val formatted = "${key.replace('_', ' ')}: $value"
                    val list = categorized[cat]
                    if (list != null && list.none { it.startsWith(key.replace('_', ' '), ignoreCase = true) }) {
                        list.add(formatted)
                    }
                }
            }
        }

        return buildString {
            appendLine("# Dynamic Learned Knowledge & User Profile")
            appendLine("These are verified facts, habits, and preferences learned about the user over time. Ground your advice, humor, and empathy in these truths:")
            for ((category, items) in categorized) {
                if (items.isNotEmpty()) {
                    appendLine("## $category")
                    for (item in items) {
                        appendLine("- $item")
                    }
                }
            }
        }.trim()
    }

    private fun categorizeKey(key: String): String {
        val k = key.lowercase()
        return when {
            k.contains("name") || k.contains("identity") || k.contains("job") || k.contains("school") ||
                k.contains("age") || k.contains("friend") || k.contains("family") || k.contains("pet") ->
                "Identity & Background"

            k.contains("bedtime") || k.contains("sleep") || k.contains("wake") || k.contains("routine") ||
                k.contains("active_window") || k.contains("nudge") || k.contains("schedule") ->
                "Circadian Rhythm & Daily Routine"

            k.contains("music") || k.contains("song") || k.contains("artist") || k.contains("game") ||
                k.contains("app") || k.contains("food") || k.contains("like") || k.contains("favorite") ||
                k.contains("genre") || k.contains("dislike") ->
                "Personal Preferences & Tastes"

            k.contains("health") || k.contains("workout") || k.contains("fitness") || k.contains("screen") ||
                k.contains("water") || k.contains("goal") || k.contains("diet") ->
                "Health, Sleep & Well-being"

            k.contains("rule") || k.contains("boundary") || k.contains("principle") || k.contains("stated") ||
                k.contains("trigger") || k.contains("promise") ->
                "User Principles & Stated Rules"

            else -> "General Knowledge & Insights"
        }
    }
}
