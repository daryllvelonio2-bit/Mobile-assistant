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
        com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "Calling Gemini API (model=$model, prompt=${ShiinaPrompts.PROMPT_VERSION})...")
        try {
            val parts = JSONArray().put(JSONObject().put("text", prompt(summary, memoryContext, senses)))

            // Attach latest screenshot if captured within the last 30 minutes
            val latestCap = getLatestRecentScreenshot()
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

            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "API error response: code=$code")
                    throw IllegalStateException("gemini $code")
                }
                val respStr = response.body?.string().orEmpty()
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
            if (ageMs < 30 * 60 * 1000L) newest else null
        }.getOrNull()
    }

    private suspend fun prompt(summary: DecisionSummary, memoryContext: String, senses: String): String {
        val base = summary.entertainmentBaseline.coerceAtLeast(1.0)
        val pctOver = ((summary.entertainmentMinutes - base) / base * 100).toInt()
        val mood = ShiinaPrompts.currentMood(episodeDao)
        val goals = runCatching {
            goalDao?.all()?.filter { it.status == 0 }?.take(3)
                ?.joinToString("; ") { it.title.take(30) }.orEmpty()
        }.getOrDefault("")
        return ShiinaPrompts.GLOBAL_RULES + " " +
            ShiinaPrompts.moodPrompt(mood) + " " +
            "INTERRUPTION POLICY: interrupting attention is expensive. ONLY interrupt if " +
            "(1) entertainment time exceeds baseline by a significant margin — use the " +
            "'Learned interrupt threshold' from MEMORY when present, otherwise >20%, " +
            "(2) bedtime passed with active screen use, or " +
            "(3) a goal deadline is imminent/overdue while non-work apps are active. " +
            "NEVER interrupt if the user is productive, within normal variance, or you " +
            "lack a concrete actionable observation. " +
            "SCREENSHOT GROUNDING: if a screenshot is attached, observe only what is " +
            "actually visible in it and incorporate it candidly. If no screenshot is " +
            "attached, never describe the screen — say you can't see it. " +
            "Every interruption must end in a binary choice, an acknowledgment, or a " +
            "local action. If you do not know why the user slipped, ask — never guess. " +
            "IDENTITY: only use the user's name if MEMORY gives user_name — never " +
            "invent, guess, or reuse a name from nowhere. If no name is listed, use none. " +
            "DEVICE SENSES (live, never look these up): " +
            "${senses.ifBlank { ShiinaPrompts.timeLine(context) }} " +
            "entertainment ${summary.entertainmentMinutes}min vs " +
            "7-day baseline ${summary.entertainmentBaseline.toInt()}min " +
            "(${if (pctOver >= 0) "+" else ""}${pctOver}% over), " +
            "goals open=${summary.goalsOpen} done=${summary.goalsDone} " +
            "missed=${summary.goalsMissed}" +
            (if (goals.isNotBlank()) " (open goal titles: $goals)" else "") + ". " +
            (if (memoryContext.isNotBlank()) {
                "MEMORY (verified past rounds and learned facts — use for continuity, " +
                    "reference real patterns like streaks when relevant): $memoryContext "
            } else "") +
            "OUTPUT RULES: reply with raw JSON only — no markdown fences, no commentary " +
            "before or after, no trailing text. tone MUST be one of " +
            "neutral|candid_direct|firm_warning|validating; any other value is treated " +
            "as neutral. spoken_message must be 140 characters or fewer, cut cleanly at " +
            "a word boundary. " +
            "PARAM RULES: LEARN_FACT needs key AND value; SET_REMINDER needs text " +
            "containing a time (\"remind me at 8pm to ...\"); READ_URL needs a full url; " +
            "SEARCH_WEB needs a query; LOG_GOAL needs a title. If you cannot fill the " +
            "parameter, use type NONE instead. " +
            "{\"should_interrupt\":<boolean>," +
            "\"confidence\":<0.0-1.0 how sure this interrupt is warranted>," +
            "\"confidence_reason\":\"<1 sentence: which metric crossed baseline>\"," +
            "\"tone\":\"<neutral|candid_direct|firm_warning|validating>\"," +
            "\"spoken_message\":\"<exact words shown on screen, or null>\"," +
            "\"recommended_action\":{\"type\":\"<NONE|SET_ALARM|TOGGLE_SCREENSHOT|LOG_GOAL|LEARN_FACT|SEARCH_WEB|TAKE_SCREENSHOT|READ_URL|CHECK_GOALS|SET_REMINDER|COMPLETE_GOAL>\"," +
            "\"parameters\":{\"key\":\"<LEARN_FACT: fact name>\"," +
            "\"value\":\"<LEARN_FACT: fact value | SEARCH_WEB: query | READ_URL: url (key \"url\") | SET_REMINDER: reminder text (key \"text\") | COMPLETE_GOAL: goal title>\"," +
            "\"title\":\"<LOG_GOAL: goal title>\"}}," +
            "\"extra_actions\":[{\"type\":\"<verb>\",\"param\":\"<parameter>\"}]} — " +
            "extra_actions holds up to 2 follow-up steps after the main action " +
            "(e.g. screenshot then search), or [] when none. " +
            "Use SEARCH_WEB when they ask something you cannot know on-device; " +
            "READ_URL to fetch the actual article a search pointed to; " +
            "CHECK_GOALS to report open/stalled goals; " +
            "COMPLETE_GOAL with the goal title when they finish one; " +
            "SET_REMINDER with text like \"remind me at 8pm to stretch\"; " +
            "use TAKE_SCREENSHOT when seeing the screen would answer them."
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
                while (i < arr.length() && extras.size < 2) {
                    val o = arr.optJSONObject(i)
                    val t = o?.optString("type", "")?.trim()?.uppercase().orEmpty()
                    if (t in Decision.ALLOWED_ACTIONS && t != "NONE") {
                        extras += t to o.optString("param", "").trim().take(140)
                    }
                    i++
                }
            }
            Decision(
                tone = tone,
                interrupt = interrupt,
                intent = Decision.intentFor(tone, action),
                action = if (interrupt) action else "NONE",
                actionParam = if (interrupt) param.take(140) else "",
                message = if (interrupt) {
                    rawMessage.take(140).ifEmpty { defaultMessage(summary, true) }
                } else "",
                confidence = confidence,
                extraActions = if (interrupt) extras else emptyList(),
            )
        } catch (_: Exception) {
            ruleBased(summary)
        }
    }

    private fun paramFor(rawAction: String, params: JSONObject?): String = when (rawAction) {
        "LEARN_FACT" -> {
            val k = params?.optString("key", "")?.trim().orEmpty()
            val v = params?.optString("value", "")?.trim().orEmpty()
            if (k.isNotEmpty()) "$k=$v" else ""
        }
        "READ_URL" -> params?.optString("url", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("value", "")?.trim().orEmpty() }
        "SET_REMINDER" -> params?.optString("text", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("value", "")?.trim().orEmpty() }
        "SEARCH_WEB" -> params?.optString("query", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("value", "")?.trim().orEmpty() }
        "COMPLETE_GOAL" -> params?.optString("value", "")?.trim().orEmpty()
            .ifEmpty { params?.optString("title", "")?.trim().orEmpty() }
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