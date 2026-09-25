package com.shiina.mobile.decision

import android.content.Context
import android.util.Base64
import com.shiina.mobile.data.db.GoalDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Gemini reasoning backend: global rules + one active mood, strict JSON out,
 * with optional recent screenshot vision.
 * Audit P1 (strict JSON terminator), P2 (learned threshold in policy),
 * P4 (tone allowlist + repair), P5 (action/param cross-validation),
 * P6 (140-char cap in prompt), P9 (screenshot-grounding rule),
 * P10 (open goal titles injected), F3 (parse validates against allowlists),
 * F4 (message sanitized), F8 (logs screenshot name only, never content).
 */
class GeminiProvider(
    private val context: Context,
    private val keys: RoundRobinKeyPool,
    private val http: OkHttpClient,
    private val episodeDao: com.shiina.mobile.data.db.MemoryEpisodeDao,
    private val model: String = "gemini-3.5-flash-lite",
    private val goalDao: GoalDao? = null,
    private val moodEngine: com.shiina.mobile.character.MoodEngine? = null,
    private val settingsRepository: com.shiina.mobile.data.settings.SettingsRepository? = null,
) : DecisionProvider {

    override val name = "gemini"
    override val supportsVision = true

    override suspend fun generate(
        summary: DecisionSummary,
        memoryContext: String,
        senses: String,
    ): Decision = withContext(Dispatchers.IO) {
        val key = keys.next() ?: run {
            com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "No Gemini API keys found in KeyStore")
            throw IllegalStateException("no gemini keys")
        }
        val activeModel = runCatching { settingsRepository?.getGeminiModel() }.getOrNull() ?: model
        com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "Calling Gemini API (model=$activeModel, prompt=${ShiinaPrompts.PROMPT_VERSION})...")
        try {
            val promptText = prompt(summary, memoryContext, senses)
            val parts = JSONArray().put(JSONObject().put("text", promptText))

            // Attach latest screenshot if captured within the last 30 minutes
            val latestCap = getLatestRecentScreenshot()
            val screenshotAttached = latestCap != null
            if (latestCap != null) {
                val bytes = latestCap.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject()
                            .put("mime_type", "image/jpeg")
                            .put("data", b64)
                    )
                )
                com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "Attached screenshot: ${latestCap.name}")
            }

            com.shiina.mobile.debug.AppDebugServer.log(
                "GEMINI_REQUEST",
                "Prompt (senses, baseline, memory, rules):\n$promptText" +
                    if (screenshotAttached) "\n[Attached screenshot: ${latestCap?.name}]" else ""
            )

            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$activeModel:generateContent?key=$key")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "API error response: code=$code")
                    throw IllegalStateException("gemini $code")
                }
                val respStr = response.body?.string().orEmpty()
                com.shiina.mobile.debug.AppDebugServer.log("GEMINI_RESPONSE", "Raw response:\n$respStr")
                com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "API success response received")
                parse(respStr, summary)
            }
        } catch (e: Exception) {
            keys.reportFailure(key)
            com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "API call failed: ${e.message}")
            throw e
        }
    }

    private fun getLatestRecentScreenshot(): File? {
        return runCatching {
            val dir = File(context.filesDir, "captures")
            val files = dir.listFiles()?.filter { it.extension.lowercase() == "jpg" } ?: return null
            val newest = files.maxByOrNull { it.lastModified() } ?: return null
            val ageMs = System.currentTimeMillis() - newest.lastModified()
            if (ageMs < 60 * 1000L) newest else null
        }.getOrNull()
    }

    private suspend fun prompt(summary: DecisionSummary, memoryContext: String, senses: String): String {
        val base = summary.entertainmentBaseline.coerceAtLeast(1.0)
        val pctOver = ((summary.entertainmentMinutes - base) / base * 100).toInt()
        // Dynamic Mood Balancing: mood comes from the persistent engine state,
        // not just the last decision tone.
        val mood = runCatching { moodEngine?.currentMood() }.getOrNull()
            ?: ShiinaPrompts.currentMood(episodeDao)
        val emotionBlock = runCatching { moodEngine?.promptBlock() }.getOrNull().orEmpty()
        val goals = runCatching {
            goalDao?.all()?.filter { it.status == 0 }?.take(3)
                ?.joinToString("; ") { it.title.take(30) }.orEmpty()
        }.getOrDefault("")
        return buildString {
            appendLine(ShiinaPrompts.GLOBAL_RULES)
            appendLine(ShiinaPrompts.moodPrompt(mood))
            if (emotionBlock.isNotBlank()) appendLine(emotionBlock)
            appendLine()

            appendLine("# Interruption & Presence Policy")
            appendLine("Setting should_interrupt to true actively presents you on screen to get the user's attention.")
            appendLine("ONLY set should_interrupt to true if:")
            appendLine("1. entertainment time exceeds baseline significantly (use 'Learned interrupt threshold' from MEMORY if present, else >20%),")
            appendLine("2. bedtime passed with active screen use, or")
            appendLine("3. a goal deadline is overdue or imminent while non-work apps are active.")
            appendLine("NEVER interrupt if the user is productive, within normal habits, or if you lack a concrete observation.")
            appendLine("SLEEP-TIME STRICTNESS: if the current hour is within the learned bedtime window (see Best nudge hour / bedtime facts) or between 11pm and 6am with entertainment apps active and the screen on, raise the bar: use firm_warning tone, lower your spoken_message to a direct command to go to sleep, and you MAY interrupt even on a modest overage. Being late at night is not 'being productive'.")
            appendLine("When should_interrupt is false, spoken_message must be null.")
            appendLine("When should_interrupt is true, spoken_message must be a short, natural, friendly observation or question (1-2 sentences, <=140 chars).")
            appendLine("SCREENSHOT GROUNDING: if a screenshot is attached, observe only what is visible in it. If none is attached, do not guess screen contents.")
            appendLine("IDENTITY: only use the user's name if MEMORY gives user_name — never guess or invent names.")
            appendLine()

            appendLine("# Live Context & Device Senses")
            appendLine("- ${senses.ifBlank { ShiinaPrompts.timeLine(context) }}")
            appendLine("- Entertainment: ${summary.entertainmentMinutes}min vs 7-day baseline ${summary.entertainmentBaseline.toInt()}min (${if (pctOver >= 0) "+" else ""}${pctOver}% over)")
            appendLine("- Goals: open=${summary.goalsOpen}, done=${summary.goalsDone}, missed=${summary.goalsMissed}${if (goals.isNotBlank()) " (open titles: $goals)" else ""}")
            // LOOP-1: real sleep data reaches the prompt when Health Connect has it.
            if (summary.sleepBedMillis > 0L) {
                val hoursSince = (System.currentTimeMillis() - summary.sleepBedMillis) / 3_600_000.0
                appendLine("- Last sleep session started ${"%.1f".format(hoursSince)}h ago (${java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(summary.sleepBedMillis))}).")
            }
            appendLine()

            if (memoryContext.isNotBlank()) {
                appendLine("# Memory (Verified Past Rounds & Learned Facts)")
                appendLine(memoryContext.trim())
                appendLine()
            }

            appendLine("# Output Specification & JSON Schema")
            appendLine("Reply with raw JSON only (no markdown fences, no trailing commentary).")
            appendLine("tone MUST be one of: neutral | candid_direct | firm_warning | validating.")
            appendLine("spoken_message must be <=140 chars.")
            appendLine("JSON structure:")
            appendLine("{\"should_interrupt\":<boolean>,\"confidence\":<0.0-1.0>,\"confidence_reason\":\"<1 short sentence: which metric triggered this>\",\"tone\":\"<neutral|candid_direct|firm_warning|validating>\",\"spoken_message\":\"<exact words spoken to user, or null>\",\"recommended_action\":{\"type\":\"<NONE|SET_ALARM|TOGGLE_SCREENSHOT|LOG_GOAL|LEARN_FACT|REMEMBER|SEARCH_WEB|TAKE_SCREENSHOT|READ_URL|CHECK_GOALS|SET_REMINDER|LIST_REMINDERS|CANCEL_REMINDER|COMPLETE_GOAL|HIDE|SET_MODE|MEDIA_CONTROL|PLAY_MUSIC|SEARCH_MUSIC|VOLUME_CONTROL|DEVICE_ACTION|OPEN_APP|SEARCH_APP|LIST_APPS|GET_DEVICE_STATE>\",\"parameters\":{\"key\":\"<LEARN_FACT/REMEMBER: fact name>\",\"value\":\"<LEARN_FACT: fact value | SEARCH_WEB: query | READ_URL: url | SET_REMINDER: text | COMPLETE_GOAL: goal title | CANCEL_REMINDER: id or text | OPEN_APP/SEARCH_APP: app | PLAY_MUSIC: song | SEARCH_MUSIC: query | MEDIA_CONTROL: play/pause/next/prev | VOLUME_CONTROL: up/down/mute>\",\"text\":\"<REMEMBER: fact value | SET_REMINDER: text>\",\"mode\":\"<SET_MODE: WANDER|STAY|VANISH>\",\"title\":\"<LOG_GOAL: goal title>\"}},\"extra_actions\":[{\"type\":\"<verb>\",\"param\":\"<parameter>\"}]}")
            appendLine("Action Guidelines:")
            appendLine("- Use SET_MODE with mode WANDER, STAY, or VANISH to manage avatar presence;")
            appendLine("- Use HIDE to hide overlay;")
            appendLine("- Use SEARCH_WEB when external info is needed;")
            appendLine("- Use TAKE_SCREENSHOT when looking at the screen would answer a question;")
            appendLine("- Use SET_REMINDER with target reminder description and scheduled time in parameters.text; LIST_REMINDERS to show pending reminders; CANCEL_REMINDER with the identifier or description to cancel one;")
            appendLine("- Use OPEN_APP with an app name, PLAY_MUSIC with an audio query, MEDIA_CONTROL with playback actions, VOLUME_CONTROL with level actions;")
            appendLine("- SET_REMINDER, LOG_GOAL, LEARN_FACT, REMEMBER, CHECK_GOALS and TAKE_SCREENSHOT may run even when should_interrupt is false — she does the chore silently.")
            appendLine("- SET_TRIGGER arms a persistent overlay at a designated future time requiring direct user acknowledgment; LIST_TRIGGERS lists active triggers; CANCEL_TRIGGER cancels a trigger by identifier. Use these for scheduled accountability checkpoints.")
        }
    }

    private fun parse(body: String, summary: DecisionSummary): Decision {
        return try {
            val text = JSONObject(body)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) throw IllegalArgumentException("no json")
            val json = JSONObject(text.substring(start, end + 1))
            val interrupt = json.optBoolean("should_interrupt", false)
            val confidence = json.optDouble("confidence", 0.7).coerceIn(0.0, 1.0)
            val reason = json.optString("confidence_reason", "").trim().take(140)
            if (reason.isNotEmpty()) {
                com.shiina.mobile.debug.AppDebugServer.log("DECISION", "Reason ($confidence): $reason")
            }
            // F3: unknown tones/actions map to safe defaults, never crash.
            val tone = json.optString("tone", "neutral").trim().lowercase()
                .takeIf { it in Decision.ALLOWED_TONES } ?: "neutral"
            // F4: strip control chars + stray fence artifacts before display.
            val rawMessage = if (json.isNull("spoken_message")) "" else
                json.optString("spoken_message", "")
                    .replace(Regex("[\\u0000-\\u001f]"), " ")
                    .replace("```", "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            val actionObj = json.optJSONObject("recommended_action")
            val rawAction = actionObj?.optString("type", "NONE")?.trim()?.uppercase() ?: "NONE"
            val action = rawAction.takeIf { it in Decision.ALLOWED_ACTIONS } ?: "NONE"
            val param = paramFor(rawAction, actionObj?.optJSONObject("parameters"))
            val extras = mutableListOf<Pair<String, String>>()
            json.optJSONArray("extra_actions")?.let { arr ->
                var i = 0
                while (i < arr.length() && extras.size < 4) {
                    val o = arr.optJSONObject(i)
                    val t = o?.optString("type", "")?.trim()?.uppercase().orEmpty()
                    if (t in Decision.ALLOWED_ACTIONS && t != "NONE") {
                        extras += t to o.optString("param", "").trim().take(140)
                    }
                    i++
                }
            }
            com.shiina.mobile.debug.AppDebugServer.log(
                "GEMINI_PARSED",
                "Parsed decision: should_interrupt=$interrupt, confidence=$confidence, reason=$reason, tone=$tone, action=$action, param=$param, message=$rawMessage, extras=$extras"
            )
            // LOOP-2: non-interrupting rounds keep silent chores (and only
            // those) — everything else is forced to NONE so she can do
            // background work without popping on screen.
            val silentOk = action == "NONE" || action in Decision.SILENT_ACTIONS
            Decision(
                tone = tone,
                interrupt = interrupt,
                intent = Decision.intentFor(tone, action),
                action = if (interrupt || action == "HIDE" || action == "SET_MODE" || (!interrupt && silentOk)) action else "NONE",
                actionParam = if (interrupt || action == "HIDE" || action == "SET_MODE" || (!interrupt && silentOk)) param.take(140) else "",
                message = if (interrupt) {
                    rawMessage.take(140).ifEmpty { defaultMessage(summary, true) }
                } else "",
                confidence = confidence,
                extraActions = if (interrupt) extras else extras.filter { it.first in Decision.SILENT_ACTIONS },
            )
        } catch (e: Exception) {
            com.shiina.mobile.debug.AppDebugServer.log(
                "GEMINI_PARSE_ERROR",
                "Failed to parse Gemini response: ${e.message}\nBody was: $body\nFalling back to rule-based decision."
            )
            ruleBased(summary)
        }
    }

    private fun paramFor(rawAction: String, params: JSONObject?): String = when (rawAction) {
        "LEARN_FACT" -> {
            val k = params?.optString("key", "")?.trim().orEmpty()
            val v = params?.optString("value", "")?.trim().orEmpty()
            if (k.isNotEmpty()) "$k=$v" else ""
        }
        "REMEMBER" -> {
            val k = params?.optString("key", "")?.trim().orEmpty()
            val v = params?.optString("text", "")?.trim().orEmpty()
                .ifEmpty { params?.optString("value", "")?.trim().orEmpty() }
            if (k.isNotEmpty()) "$k=$v" else v
        }
        "READ_URL" -> params?.optString("url", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("value", "")?.trim().orEmpty() }
        "SET_REMINDER" -> params?.optString("text", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("value", "")?.trim().orEmpty() }
        "SET_TRIGGER" -> params?.optString("text", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("value", "")?.trim().orEmpty() }
        "LIST_TRIGGERS" -> ""
        "CANCEL_TRIGGER" -> params?.optString("value", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("text", "")?.trim().orEmpty() }
        "LIST_REMINDERS" -> ""
        "CANCEL_REMINDER" -> params?.optString("value", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("text", "")?.trim().orEmpty() }
        "SEARCH_WEB" -> params?.optString("query", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("value", "")?.trim().orEmpty() }
        "COMPLETE_GOAL" -> params?.optString("value", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("title", "")?.trim().orEmpty() }
        "SET_MODE" -> params?.optString("mode", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("value", "")?.trim().orEmpty() }
        "OPEN_APP" -> params?.optString("value", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("title", "")?.trim().orEmpty() }
        "SEARCH_APP" -> params?.optString("value", "")?.trim().orEmpty()
        "PLAY_MUSIC", "SEARCH_MUSIC" -> params?.optString("value", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("query", "")?.trim().orEmpty() }
        "MEDIA_CONTROL", "VOLUME_CONTROL" -> params?.optString("value", "")?.trim().orEmpty()
        else -> params?.optString("title", "")?.trim().orEmpty()
    }

    private fun ruleBased(summary: DecisionSummary): Decision {
        val base = summary.entertainmentBaseline.coerceAtLeast(1.0)
        val over = (summary.entertainmentMinutes - base) / base
        return when {
            over > 0.5 -> Decision(
                tone = "firm_warning",
                interrupt = true,
                intent = "coach",
                action = "NONE",
                actionParam = "",
                message = "You're ${summary.entertainmentMinutes}min deep against a " +
                    "${summary.entertainmentBaseline.toInt()}min baseline. Close it or set an alarm?",
                confidence = 0.9,
            )
            over > 0.2 -> Decision(
                tone = "candid_direct",
                interrupt = true,
                intent = "coach",
                action = "NONE",
                actionParam = "",
                message = "Over baseline by a clear margin. Winding down now?",
                confidence = 0.8,
            )
            else -> Decision.fallback("neutral", "")
        }
    }

    private fun defaultMessage(summary: DecisionSummary, drifted: Boolean): String =
        if (drifted) {
            "Screen time ${summary.entertainmentMinutes}min is over your " +
                "${summary.entertainmentBaseline.toInt()}min baseline. Time to wind down?"
        } else {
            "On track. Screen time ${summary.entertainmentMinutes}min vs " +
                "${summary.entertainmentBaseline.toInt()}min baseline."
        }
}