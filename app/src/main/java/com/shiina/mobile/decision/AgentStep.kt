package com.shiina.mobile.decision

import org.json.JSONObject

/**
 * Structured step emitted by the autonomous agent during the Think-Act-Observe-Verify loop.
 */
data class AgentStep(
    val thought: String = "",
    val mood: String = "",
    val status: String = "DONE", // "CONTINUE" or "DONE"
    val tool: String = "NONE",
    val nextCheckInMinutes: Int = -1,
    val toolset: String = "",
    val query: String = "",
    val url: String = "",
    val key: String = "",
    val value: String = "",
    val text: String = "",
    val mode: String = "",
    val title: String = "",
    val action: String = "",
    val app: String = "",
    val player: String = "",
    val filter: String = "",
    val level: Int = -1,
    val hour: Int = -1,
    val minute: Int = -1,
    val seconds: Int = -1,
    val elementId: Int = -1,
    val x: Float = -1f,
    val y: Float = -1f,
    val startX: Float = -1f,
    val startY: Float = -1f,
    val endX: Float = -1f,
    val endY: Float = -1f,
    val direction: String = "",
    val isLongPress: Boolean = false,
    val message: String = "",
)

object AgentStepParser {

    /**
     * Resilient parser that extracts and validates an AgentStep from raw LLM output.
     * Strips Markdown wrappers, ignores outer commentary, handles alternative key namings,
     * and shields against JSON speech leakage.
     */
    fun parse(raw: String, knownTools: Set<String>): AgentStep? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```")
            if (text.startsWith("json", ignoreCase = true)) text = text.removeRange(0, 4)
            val end = text.lastIndexOf("```")
            if (end >= 0) text = text.substring(0, end)
            text = text.trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        text = text.substring(start, end + 1)

        return runCatching {
            val json = JSONObject(text)
            val toolRaw = json.optString("tool", "NONE").uppercase().trim()
            val tool = if (toolRaw in knownTools) toolRaw else "NONE"
            val nextCheckInMinutes = json.optInt("next_check_in_minutes", json.optInt("nextCheckInMinutes", -1))
            val statusRaw = json.optString("status", "").uppercase().trim()
            val status = when {
                statusRaw in setOf("CONTINUE", "DONE") -> statusRaw
                tool != "NONE" -> "CONTINUE"
                else -> "DONE"
            }
            val moodRaw = json.optString("mood", "").lowercase().trim()
            val mood = when (moodRaw) {
                "pouty", "sulky" -> "pouty"
                "candid", "candid_direct" -> "candid"
                "firm", "firm_warning" -> "firm"
                "warm", "validating" -> "warm"
                "calm", "neutral" -> "calm"
                "excited" -> "excited"
                "melancholy" -> "melancholy"
                else -> ""
            }
            var message = json.optString("message", "")
                .ifEmpty { json.optString("answer", "") }
                .ifEmpty { json.optString("text", "") }
            if (message.contains("\"candidates\"") || message.contains("\"finishReason\"") || message.contains("\"error\"") || message.contains("\"thought\":")) {
                message = ""
            }

            val args = json.optJSONObject("tool_args") ?: json
            val query = args.optString("query", "")
                .ifEmpty { args.optString("song", "") }
                .ifEmpty { args.optString("track", "") }
                .ifEmpty { args.optString("title", "") }
            val level = args.optInt("level", -1)
            val elementId = args.optInt("element_id", -1).let { if (it > 0) it else json.optInt("element_id", -1) }
            val isLongPress = args.optBoolean("is_long_press", false)
            val textArg = args.optString("text", "").ifEmpty { args.optString("data", "") }.trim()
            val titleArg = args.optString("title", "").trim()

            var hour = args.optInt("hour", -1).let { if (it in 0..23) it else -1 }
            var minute = args.optInt("minute", -1).let { if (it in 0..59) it else -1 }
            var seconds = args.optInt("seconds", -1).let { if (it > 0) it else args.optInt("length", -1) }

            if (tool == "SET_ALARM") {
                if (hour == -1 && level in 0..23) {
                    hour = level
                }
                if (minute == -1) {
                    val qNum = query.toIntOrNull()
                    if (qNum != null && qNum in 0..59) {
                        minute = qNum
                    }
                }
                if (hour == -1) {
                    val combinedTimeStr = "$textArg $query $titleArg".trim()
                    val timeMatch = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""", RegexOption.IGNORE_CASE).find(combinedTimeStr)
                    if (timeMatch != null) {
                        val rawH = timeMatch.groupValues[1].toIntOrNull() ?: -1
                        val rawM = timeMatch.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
                        val ampm = timeMatch.groupValues[3].lowercase()
                        if (rawH in 0..23) {
                            hour = when {
                                ampm == "pm" && rawH < 12 -> rawH + 12
                                ampm == "am" && rawH == 12 -> 0
                                else -> rawH
                            }
                            minute = rawM
                        }
                    }
                }
            } else if (tool == "SET_TIMER") {
                if (seconds <= 0 && level > 0) {
                    seconds = level
                }
                if (seconds <= 0) {
                    val combinedTimerStr = "$textArg $query $titleArg".trim()
                    val timerMatch = Regex("""(\d+)\s*(s|sec|second|seconds|m|min|minute|minutes|h|hr|hour|hours)""", RegexOption.IGNORE_CASE).find(combinedTimerStr)
                    if (timerMatch != null) {
                        val num = timerMatch.groupValues[1].toIntOrNull() ?: 0
                        val unit = timerMatch.groupValues[2].lowercase()
                        seconds = when {
                            unit.startsWith("h") -> num * 3600
                            unit.startsWith("m") -> num * 60
                            else -> num
                        }
                    } else {
                        val pureNum = query.toIntOrNull() ?: textArg.toIntOrNull()
                        if (pureNum != null && pureNum > 0) seconds = pureNum
                    }
                }
            }

            AgentStep(
                thought = json.optString("thought", "").trim(),
                mood = mood,
                status = status,
                tool = tool,
                nextCheckInMinutes = nextCheckInMinutes,
                toolset = args.optString("toolset", "").trim().lowercase(),
                query = query.trim(),
                url = args.optString("url", "").trim(),
                key = args.optString("key", "").trim(),
                value = args.optString("value", "").trim(),
                text = textArg,
                mode = args.optString("mode", "").trim().uppercase(),
                title = titleArg,
                action = args.optString("action", "").trim(),
                app = args.optString("app", "").trim(),
                player = args.optString("player", "").trim().lowercase(),
                filter = args.optString("filter", "").trim(),
                level = level,
                hour = hour,
                minute = minute,
                seconds = seconds,
                elementId = elementId,
                x = args.optDouble("x", -1.0).toFloat(),
                y = args.optDouble("y", -1.0).toFloat(),
                startX = args.optDouble("startX", -1.0).toFloat(),
                startY = args.optDouble("startY", -1.0).toFloat(),
                endX = args.optDouble("endX", -1.0).toFloat(),
                endY = args.optDouble("endY", -1.0).toFloat(),
                direction = args.optString("direction", "").trim().lowercase(),
                isLongPress = isLongPress,
                message = message.trim(),
            )
        }.getOrNull()
    }
}
