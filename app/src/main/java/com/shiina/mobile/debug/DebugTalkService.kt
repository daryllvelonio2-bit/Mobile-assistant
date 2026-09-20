package com.shiina.mobile.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.shiina.mobile.CompanionApp
import com.shiina.mobile.character.CharacterMode
import com.shiina.mobile.character.CharacterOverlayService
import com.shiina.mobile.decision.DecisionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Persistent notification debug panel (text-based talk for now, voice later).
 * Actions: render latest decision, view current tone, toggle debug logs,
 * plus notification RemoteInput reply for text chat with Gemini.
 */
class DebugTalkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()

    @Volatile private var lastTone: String = "calm"
    @Volatile private var lastMode: String = CharacterMode.STAY.name
    @Volatile private var lastReply: String = "Say hi from the notification reply."
    @Volatile private var logsEnabled: Boolean = true

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Shiina debug", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(NOTIF_ID, buildPanel(null)) }
            .onFailure {
                AppDebugServer.log("ERROR", "DebugTalk startForeground failed: ${it.message}")
                stopSelf()
                return START_NOT_STICKY
            }
        runCatching {
            when (intent?.action) {
                ACTION_RENDER -> renderLatest()
                ACTION_TOGGLE_LOGS -> {
                    logsEnabled = !logsEnabled
                    dbg("Debug logs ${if (logsEnabled) "ON" else "OFF"}")
                    refresh()
                }
                ACTION_TALK -> handleTalk(intent)
                ACTION_HIDE -> hideOverlay()
                ACTION_RESET_SESSION -> {
                    lastTone = "calm"
                    lastMode = CharacterMode.STAY.name
                    lastReply = "Say hi from the notification reply."
                    dbg("Shiina: session reset (memory cleared from Settings)")
                }
            }
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "DebugTalk action failed: ${e.message}")
        }
        // Always refresh panel so tone/reply stays visible.
        runCatching { refresh() }
        return START_STICKY
    }

    private fun dbg(msg: String) {
        if (logsEnabled) AppDebugServer.log("DEBUG_PANEL", msg)
    }

    private fun renderLatest() {
        scope.launch {
            runCatching {
                val container = (application as CompanionApp).container
                val minutes = withContext(Dispatchers.IO) {
                    container.usageReader.getTodayEntertainmentMinutes()
                }
                val baseline = container.baselineUpdater.getEntertainmentBaseline()
                dbg("Usage minutes: $minutes, baseline: $baseline")
                val decision = container.providerRegistry.decide(
                    DecisionSummary(
                        entertainmentMinutes = minutes,
                        entertainmentBaseline = baseline,
                        sleepBedMillis = 0L,
                        sleepBaselineMillis = 0L,
                        goalsOpen = 0,
                        goalsDone = 0,
                        goalsMissed = 0,
                    ),
                    source = "debug",
                )
                lastTone = decision.tone
                lastReply = decision.message.ifEmpty { "On track. Nothing to report." }
                dbg(
                    "Decision: tone=${decision.tone}, intent=${decision.intent}, " +
                        "action=${decision.action}, interrupt=${decision.interrupt}",
                )
                showOverlay(decision.tone, decision.interrupt, lastReply)
                runCatching { container.actionExecutor.executeDecision(decision) }
                withContext(Dispatchers.Main) { refresh() }
            }.onFailure { e ->
                AppDebugServer.log("ERROR", "DebugTalk render failed: ${e.message}")
            }
        }
    }

    private fun showOverlay(tone: String, interrupt: Boolean, message: String) {
        runCatching {
            val mode = if (interrupt) CharacterMode.WANDER.name else CharacterMode.STAY.name
            lastMode = mode
            val i = Intent(this, CharacterOverlayService::class.java).apply {
                action = CharacterOverlayService.ACTION_SHOW
                putExtra(CharacterOverlayService.EXTRA_TONE, tone)
                putExtra(CharacterOverlayService.EXTRA_MESSAGE, message)
                putExtra(CharacterOverlayService.EXTRA_MODE, mode)
            }
            startForegroundService(i)
            dbg("Overlay shown (tone=$tone)")
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "DebugTalk show overlay failed: ${e.message}")
        }
    }

    private fun hideOverlay() {
        runCatching {
            val i = Intent(this, CharacterOverlayService::class.java).apply {
                action = CharacterOverlayService.ACTION_HIDE
            }
            startService(i)
            dbg("Overlay hide requested")
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "DebugTalk hide failed: ${e.message}")
        }
    }

    private fun handleTalk(intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        // Audit U5: cap RemoteInput text so a paste bomb can't blow the prompt.
        val text = results.getCharSequence(KEY_TALK)?.toString()?.trim()?.take(500).orEmpty()
        if (text.isEmpty()) return
        dbg("You: $text")
        val container = (application as CompanionApp).container
        scope.launch {
            runCatching { container.memoryOutcomes.onTalkReply() }
        }
        if (text.startsWith("remember ", ignoreCase = true)) {
            scope.launch {
                lastReply = runCatching {
                    container.memoryStore.rememberCommand(text.substringAfter(" ").trim())
                }.getOrElse { "save failed: ${it.message}" }
                dbg("Shiina: $lastReply")
                withContext(Dispatchers.Main) { refresh() }
            }
            return
        }
        if (text.equals("forget everything", ignoreCase = true)) {
            scope.launch {
                runCatching { container.memoryStore.forgetAll() }
                lastTone = "calm"
                lastMode = CharacterMode.STAY.name
                lastReply = "Memory wiped. Episodes, facts, chat, digests — gone."
                dbg("Shiina: $lastReply")
                withContext(Dispatchers.Main) { refresh() }
            }
            return
        }
        if (isCorrectionCommand(text)) {
            scope.launch {
                lastReply = runCatching { applyCorrection(text) }
                    .getOrElse { "save failed: ${it.message}" }
                dbg("Shiina: $lastReply")
                withContext(Dispatchers.Main) { refresh() }
            }
            return
        }
        scope.launch {
            var extra = ""
            var attachShot = false
            if (isSearchAsk(text)) {
                val q = searchQuery(text)
                val answer = runCatching { container.webSearch.search(q) }.getOrDefault("")
                extra = if (answer.isNotEmpty()) {
                    "Web results for \"$q\": $answer Answer from these. "
                } else {
                    "Web search for \"$q\" returned nothing. Say so briefly. "
                }
            } else if (isScreenshotAsk(text)) {
                val file = runCatching { container.screenshotTaker.capture("talk") }.getOrNull()
                if (file != null) {
                    attachShot = true
                    extra = "You just took a screenshot of the screen — it is attached. Describe what you see. "
                } else {
                    extra = "Screen capture is unavailable (no MediaProjection consent yet — they grant it from MainActivity). Say so briefly. "
                }
            }
            val reply = runCatching { askGemini(text, lastTone, lastMode, extra, attachShot) }.getOrElse { e ->
                AppDebugServer.log("ERROR", "DebugTalk chat failed: ${e.message}")
                "Gemini unreachable (${e.message ?: "error"}). Check API keys in Settings."
            }
            lastReply = reply
            runCatching { container.chatHistory.addShiina(reply) }
            dbg("Shiina: $reply")
            pushTextToOverlay(reply)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    /** "search X" / "look up X" / "google X" — answer from live web results. */
    private fun isSearchAsk(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("search ", ignoreCase = true) ||
            t.startsWith("search for ", ignoreCase = true) ||
            t.startsWith("look up ", ignoreCase = true) ||
            t.startsWith("lookup ", ignoreCase = true) ||
            t.startsWith("google ", ignoreCase = true)
    }

    private fun searchQuery(text: String): String {
        val t = text.trim()
        return when {
            t.startsWith("search for ", ignoreCase = true) -> t.substring(11)
            t.startsWith("search ", ignoreCase = true) -> t.substring(7)
            t.startsWith("look up ", ignoreCase = true) -> t.substring(8)
            t.startsWith("lookup ", ignoreCase = true) -> t.substring(7)
            t.startsWith("google ", ignoreCase = true) -> t.substring(7)
            else -> t
        }.trim()
    }

    /** "take a screenshot" / "what do you see" — capture now, answer with vision. */
    private fun isScreenshotAsk(text: String): Boolean {
        val t = text.trim().lowercase()
        return "screenshot" in t || "what do you see" in t ||
            "what's on my screen" in t || "what is on my screen" in t
    }

    /**
     * Correction learning without an API call — fully deterministic. Handles
     * identity fixes ("my name is X", "don't call me X"), single-fact
     * deletion ("forget bedtime"), and honest guidance for anything vaguer
     * (stores nothing it cannot parse).
     */
    private fun isCorrectionCommand(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("my name is ", ignoreCase = true) ||
            t.startsWith("forget ", ignoreCase = true) ||
            Regex("^(don't|dont|do not) call me ", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
            Regex("^(no|nope|wrong|incorrect|actually|correction)[,.! ]", RegexOption.IGNORE_CASE)
                .containsMatchIn(t)
    }

    private suspend fun applyCorrection(text: String): String {
        val container = (application as CompanionApp).container
        val t = text.trim()
        Regex("^my name is (.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            val name = it.groupValues[1].trim().take(64)
            if (name.isEmpty()) return "Tell me your name like: my name is ..."
            container.memoryStore.remember("user_name", name, "stated")
            return "Nice to meet you properly, $name. I won't forget again."
        }
        Regex("^(don't|dont|do not) call me (.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            val had = container.memoryStore.contradict("user_name")
            return if (had) {
                "Got it — I dropped that name. Tell me yours with: my name is ..."
            } else {
                "I don't have a name saved for you — tell me with: my name is ..."
            }
        }
        Regex("^forget (.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            val key = it.groupValues[1].trim().take(64)
            val ok = container.memoryStore.forgetKey(key)
            return if (ok) "Forgot $key." else "I don't remember anything called $key."
        }
        return "Noted — give me the fix as: remember key=value."
    }

    /** One parsed tool call from her JSON. Null = she answered directly. */
    private data class ToolCall(
        val tool: String,
        val query: String = "",
        val url: String = "",
        val key: String = "",
        val value: String = "",
        val text: String = "",
        val answer: String = "",
    )

    private val KNOWN_TOOLS = setOf(
        "SEARCH_WEB", "READ_URL", "TAKE_SCREENSHOT", "REMEMBER",
        "CHECK_GOALS", "SET_REMINDER", "COMPLETE_GOAL", "NONE",
    )

    private fun parseToolCall(raw: String): ToolCall? {
        // She often wraps the JSON in markdown fences (```json ... ```) —
        // strip them before parsing, else every tool call leaks to the user.
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
        if (start >= 0 && end > start) text = text.substring(start, end + 1)
        return runCatching {
            val json = JSONObject(text)
            if (!json.has("tool")) return null
            val tool = json.optString("tool", "NONE").uppercase()
            if (tool !in KNOWN_TOOLS) return null
            ToolCall(
                tool = tool,
                query = json.optString("query", ""),
                url = json.optString("url", ""),
                key = json.optString("key", ""),
                value = json.optString("value", ""),
                text = json.optString("text", ""),
                answer = json.optString("answer", ""),
            )
        }.getOrNull()
    }

    /**
     * Talk harness: fast path (keyword triggers already resolved a tool) goes
     * straight to a direct answer; otherwise round 1 runs the JSON tool
     * protocol, the harness executes, and a final round answers off the tool
     * result. SEARCH_WEB retries once with a rephrased query when the first
     * search comes back empty — max 2 searches per turn, then she answers
     * with whatever she has instead of giving up on nothing.
     */
    private suspend fun askGemini(
        userText: String,
        tone: String,
        mode: String,
        extraContext: String = "",
        attachScreenshot: Boolean = false,
    ): String {
        val container = (application as CompanionApp).container
        val history = runCatching { container.chatHistory.recentTurns() }.getOrDefault(emptyList())
            .joinToString("\n") { "${it.role}: ${it.text}" }
        runCatching { container.chatHistory.addUser(userText) }
        val memory = runCatching { container.memoryContext.build() }.getOrDefault("")
        val userName = runCatching { container.memoryStore.factValue("user_name") }.getOrNull()
        val identityRule = if (userName != null) {
            "The owner's name is $userName. Use it naturally. "
        } else {
            "You do not know the owner's name — never invent or guess one, use none. "
        }
        val P = com.shiina.mobile.decision.ShiinaPrompts
        val base = P.GLOBAL_RULES + " " +
            P.moodPrompt(P.moodForTone(tone)) + " " + identityRule +
            (if (memory.isNotBlank()) "What you remember (weave it in when relevant): $memory " else "") +
            P.TALK_DRIVE + " " +
            "DEVICE SENSES (live, never look these up): " +
            runCatching { container.deviceSenses.snapshot() }.getOrDefault("{}") + " " +
            P.timeLine(this) + " " +
            (if (history.isNotBlank()) "Recent conversation so far:\n$history\n" else "") +
            "Owner says: $userText"
        if (extraContext.isNotBlank() || attachScreenshot) {
            return postText(base + " " + extraContext, attachScreenshot)
        }
        var prompt = "$base ${P.TOOL_SPEC}"
        val steps = mutableListOf<String>()
        var lastSig = ""
        var answer = ""
        var calls = 0
        var attachShot = false
        while (calls < MAX_TOOL_CALLS) {
            val raw = postText(prompt, attachShot)
            attachShot = false
            val call = parseToolCall(raw)
            if (call == null || call.tool == "NONE") {
                answer = (call?.answer ?: raw).trim().take(512)
                break
            }
            val sig = call.tool + "|" + (call.query + call.url + call.key).take(120)
            if (sig == lastSig) {
                // Track C1: she's repeating the same call — stop the loop honestly.
                answer = "I keep reaching for the same thing and it's not working. Can you say it a bit differently?"
                break
            }
            lastSig = sig
            calls++
            val result = runTool(call)
            if (result.second) attachShot = true
            runCatching { container.toolTracker.record(call.tool.lowercase(), result.first.isNotEmpty()) }
            steps += "${call.tool}: ${result.first.take(100).ifEmpty { "empty" }}"
            AppDebugServer.log(
                "DEBUG_PANEL", "Tool ${call.tool} -> ${result.first.take(80).ifEmpty { "(empty)" }}",
            )
            val feed = result.first.ifEmpty {
                "${call.tool} came back empty — say exactly that you tried and got nothing, never invent."
            }
            prompt = "$base Tool result — $feed " +
                "Steps so far: ${steps.joinToString("; ")}. " +
                (if (calls < MAX_TOOL_CALLS) {
                    "Call another tool if needed, or answer. Reply JSON ONLY."
                } else {
                    "All $MAX_TOOL_CALLS tool calls used — give your final answer now in plain " +
                        "text (not JSON), honest about what worked and what didn't."
                })
        }
        if (answer.isEmpty()) {
            answer = postText(
                "$base Steps taken: ${steps.joinToString("; ").ifEmpty { "none" }}. " +
                    "Answer the owner now in plain text (not JSON), honest about what you " +
                    "found and what came back empty.",
                attachShot,
            )
        }
        if (calls >= 2) {
            // Track C3: self-check — does this actually answer the ask?
            answer = postText(
                "$base Your draft answer to \"$userText\": $answer — if it answers the " +
                    "ask, return it unchanged in plain text; if it dodges, pads, or " +
                    "invents, rewrite it honestly. Plain text only.",
                false,
            )
        }
        return answer.trim().take(512).ifEmpty { "(empty reply)" }
    }

    /** Executes one parsed tool call -> (result text, needs screenshot attach). */
    private suspend fun runTool(call: ToolCall): Pair<String, Boolean> {
        val container = (application as CompanionApp).container
        return when (call.tool) {
            "SEARCH_WEB" ->
                runCatching { container.webSearch.search(call.query.ifBlank { "latest" }) }
                    .getOrDefault("") to false
            "READ_URL" ->
                runCatching { container.pageReader.read(call.url) }.getOrDefault("") to false
            "TAKE_SCREENSHOT" -> {
                val f = runCatching { container.screenshotTaker.capture("talk") }.getOrNull()
                if (f != null) "screenshot captured" to true else "" to false
            }
            "REMEMBER" -> {
                if (call.key.isNotEmpty() && call.value.isNotEmpty()) {
                    runCatching { container.memoryStore.remember(call.key, call.value, "stated") }
                    "remembered ${call.key}" to false
                } else "remember needs key and value" to false
            }
            "CHECK_GOALS" ->
                runCatching { container.actionExecutor.goalReport() }.getOrDefault("no goals") to false
            "SET_REMINDER" -> {
                val msg = runCatching {
                    container.reminderScheduler.parseAndSchedule(
                        call.text.ifEmpty { call.query },
                    ).orEmpty()
                }.getOrDefault("")
                (msg.ifEmpty { "no time found in the reminder — ask when" }) to false
            }
            "COMPLETE_GOAL" ->
                runCatching { container.actionExecutor.completeGoalPublic(call.text.ifEmpty { call.query }) }
                    .getOrDefault("complete_goal failed") to false
            else -> "" to false
        }
    }

    /** Single Gemini text call with optional vision attach. Plain text out. */
    private suspend fun postText(prompt: String, attachShot: Boolean): String {
        val container = (application as CompanionApp).container
        val key = container.keyStore.getKeys("gemini").firstOrNull()
            ?: throw IllegalStateException("no gemini keys")
        val parts = org.json.JSONArray().put(
            JSONObject().put("text", prompt),
        )
        if (attachShot) {
            val shot = runCatching { container.screenshotTaker.latestCapture() }.getOrNull()
            if (shot != null) {
                val bytes = runCatching { shot.readBytes() }.getOrNull()
                if (bytes != null) {
                    parts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", "image/jpeg")
                                .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)),
                        ),
                    )
                }
            }
        }
        val body = JSONObject()
            .put(
                "contents", org.json.JSONArray().put(
                    JSONObject().put(
                        "parts", parts,
                    ),
                ),
            )
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$key")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("gemini ${response.code}")
            val raw = response.body?.string().orEmpty()
            return runCatching {
                JSONObject(raw)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim().take(512)
            }.getOrDefault(raw.trim().take(512).ifEmpty { "(empty reply)" })
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val i = Intent(this, DebugTalkService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** RemoteInput reply actions must use a MUTABLE PendingIntent or the post is rejected. */
    private fun mutableActionIntent(action: String, code: Int): PendingIntent {
        val i = Intent(this, DebugTalkService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    /** Push chat text to the floating overlay without changing its mode. */
    private fun pushTextToOverlay(message: String) {
        runCatching {
            val i = Intent(this, CharacterOverlayService::class.java).apply {
                action = CharacterOverlayService.ACTION_SHOW
                putExtra(CharacterOverlayService.EXTRA_TONE, lastTone)
                putExtra(CharacterOverlayService.EXTRA_MESSAGE, message)
                putExtra(CharacterOverlayService.EXTRA_MODE, lastMode)
            }
            startForegroundService(i)
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "DebugTalk push text failed: ${e.message}")
        }
    }

    private fun buildPanel(headsUp: String?): Notification {
        val remoteInput = RemoteInput.Builder(KEY_TALK).setLabel("Talk to Shiina (text debug)").build()
        val talkIntent = mutableActionIntent(ACTION_TALK, 3)
        val talkAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_dialog_email, "Talk", talkIntent,
        ).addRemoteInput(remoteInput).build()
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Shiina debug · tone=$lastTone · logs=${if (logsEnabled) "ON" else "OFF"}")
            .setContentText(headsUp ?: "Her words float above the bubble — tap Talk to chat.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tone: $lastTone · Mode: $lastMode"))
            .addAction(android.R.drawable.ic_media_play, "Render", actionIntent(ACTION_RENDER, 1))
            .addAction(talkAction)
            .addAction(
                android.R.drawable.ic_menu_info_details, "Logs",
                actionIntent(ACTION_TOGGLE_LOGS, 2),
            )
            .build()
    }

    private fun refresh() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIF_ID, buildPanel(null))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "shiina_debug"
        private const val NOTIF_ID = 3
        const val ACTION_RENDER = "com.shiina.mobile.debug.RENDER"
        const val ACTION_TOGGLE_LOGS = "com.shiina.mobile.debug.TOGGLE_LOGS"
        const val ACTION_TALK = "com.shiina.mobile.debug.TALK"
        const val ACTION_HIDE = "com.shiina.mobile.debug.HIDE"
        const val ACTION_RESET_SESSION = "com.shiina.mobile.debug.RESET_SESSION"
        const val KEY_TALK = "key_talk"
        const val MAX_TOOL_CALLS = 4

        fun start(context: Context) {
            runCatching {
                val i = Intent(context, DebugTalkService::class.java)
                context.startForegroundService(i)
            }.onFailure { e ->
                AppDebugServer.log("ERROR", "DebugTalk start failed: ${e.message}")
            }
        }
    }
}
