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
        scope.launch {
            val container = (application as CompanionApp).container
            val recentTone = runCatching { container.memoryEpisodeDao.recent(1).firstOrNull()?.tone }.getOrNull()
            if (!recentTone.isNullOrBlank()) {
                lastTone = com.shiina.mobile.decision.ShiinaPrompts.moodForTone(recentTone)
                withContext(Dispatchers.Main) { refresh() }
            }
        }
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
            } else if (isMusicAsk(text)) {
                val music = container.musicTracker.getExactMusic()
                val isPlaying = music?.isPlaying ?: container.deviceSenses.isMusicPlaying()
                extra = if (music != null && isPlaying) {
                    "The user is currently listening to: \"${music.title}\" by ${music.artist}${if (music.album.isNotBlank()) " (${music.album})" else ""}${if (music.app.isNotBlank()) " via ${music.app}" else ""}. Tell them what is playing naturally and concisely (1 short sentence). "
                } else {
                    "No music is currently playing on the device. Tell them so directly and briefly. "
                }
            }
            val reply = runCatching { askGemini(text, lastTone, lastMode, extra, attachShot) }.getOrElse { e ->
                AppDebugServer.log("ERROR", "DebugTalk chat failed: ${e.message}")
                "Gemini unreachable (${e.message ?: "error"}). Check API keys in Settings."
            }
            lastReply = reply
            runCatching { container.chatHistory.addShiina(reply) }
            dbg("Shiina ($lastTone): $reply")
            pushTextToOverlay(reply)
            runCatching {
                container.memoryEpisodeDao.insert(
                    com.shiina.mobile.data.db.MemoryEpisode(
                        timestampMillis = System.currentTimeMillis(),
                        source = "talk",
                        entertainmentMinutes = 0,
                        entertainmentBaseline = 0.0,
                        goalsOpen = 0,
                        goalsDone = 0,
                        goalsMissed = 0,
                        tone = lastTone,
                        interrupt = false,
                        action = "NONE",
                        messageHash = reply.hashCode(),
                    )
                )
            }
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

    /** "what song is playing" / "what music" / "anong kanta to" — answer from live music tracker. */
    private fun isMusicAsk(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.contains("what music") || t.contains("what song") ||
            t.contains("what track") || t.contains("what am i listening") ||
            t.contains("what's playing") || t.contains("whats playing") ||
            t.contains("who is singing") || t.contains("current song") ||
            t.contains("current music") || t.contains("song is playing") ||
            t.contains("music is playing") || t.contains("anong kanta") ||
            t.contains("anong tugtog") || t.contains("anong pinapatugtog")
    }

    /**
     * Correction learning without an API call — fully deterministic. Handles
     * identity fixes ("my name is X", "don't call me X"), single-fact
     * deletion ("forget bedtime"), and honest guidance for anything vaguer
     * (stores nothing it cannot parse).
     */
    private val STATE_WORDS = setOf(
        "bored", "fine", "tired", "busy", "happy", "sad", "here", "back", "ready",
        "okay", "ok", "good", "hungry", "sleepy", "done", "doing", "just", "not",
        "so", "still", "going", "free", "asleep", "awake", "home", "out", "lost",
    )

    private fun isCorrectionCommand(text: String): Boolean {
        val t = text.trim()
        val nameMatch = Regex("^(?:i am|i'm|im) ([a-zA-Z]+)$", RegexOption.IGNORE_CASE).find(t)
        val isNameIntro = nameMatch != null && nameMatch.groupValues[1].lowercase() !in STATE_WORDS
        return t.startsWith("my name is ", ignoreCase = true) ||
            t.startsWith("call me ", ignoreCase = true) ||
            isNameIntro ||
            t.startsWith("forget ", ignoreCase = true) ||
            Regex("^(don't|dont|do not) call me ", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
            Regex("^(no|nope|wrong|incorrect|actually|correction)[,.! ]", RegexOption.IGNORE_CASE)
                .containsMatchIn(t)
    }

    private suspend fun applyCorrection(text: String): String {
        val container = (application as CompanionApp).container
        val t = text.trim()
        Regex("^(?:my name is|call me) (.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            val name = it.groupValues[1].trim().take(64)
            if (name.isEmpty()) return "Tell me your name like: my name is ..."
            container.learnedMemoryManager.learn("user_name", name, "stated")
            return "Nice to meet you properly, $name. I won't forget again."
        }
        Regex("^(?:i am|i'm|im) ([a-zA-Z]+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            val name = it.groupValues[1].trim()
            if (name.lowercase() !in STATE_WORDS) {
                val formatted = name.replaceFirstChar { c -> c.uppercase() }
                container.learnedMemoryManager.learn("user_name", formatted, "stated")
                return "Nice to meet you properly, $formatted. I won't forget again."
            }
        }
        Regex("^(don't|dont|do not) call me (.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            val had = container.memoryStore.contradict("user_name")
            runCatching { container.learnedMemoryManager.forget("user_name") }
            return if (had) {
                "Got it — I dropped that name. Tell me yours with: my name is ..."
            } else {
                "I don't have a name saved for you — tell me with: my name is ..."
            }
        }
        Regex("^forget (.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            val key = it.groupValues[1].trim().take(64)
            val ok = container.memoryStore.forgetKey(key)
            runCatching { container.learnedMemoryManager.forget(key) }
            return if (ok) "Forgot $key." else "I don't remember anything called $key."
        }
        return "Noted — give me the fix as: remember key=value."
    }

    /** One parsed step from the autonomous agent Think-Act-Observe loop. */
    private data class AgentStep(
        val thought: String = "",
        val mood: String = "",
        val status: String = "DONE", // "CONTINUE" or "DONE"
        val tool: String = "NONE",
        val query: String = "",
        val url: String = "",
        val key: String = "",
        val value: String = "",
        val text: String = "",
        val mode: String = "",
        val title: String = "",
        val message: String = "",
    )

    private val KNOWN_TOOLS = setOf(
        "LEARN", "SEARCH_WEB", "READ_URL", "TAKE_SCREENSHOT", "REMEMBER",
        "CHECK_GOALS", "SET_REMINDER", "COMPLETE_GOAL", "SET_MODE", "NONE",
    )

    private fun parseAgentStep(raw: String): AgentStep? {
        // Strip markdown code fences if wrapped
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
            val toolRaw = json.optString("tool", "NONE").uppercase()
            val tool = if (toolRaw in KNOWN_TOOLS) toolRaw else "NONE"
            val statusRaw = json.optString("status", "").uppercase()
            val status = when {
                statusRaw in setOf("CONTINUE", "DONE") -> statusRaw
                tool != "NONE" -> "CONTINUE"
                else -> "DONE"
            }
            val moodRaw = json.optString("mood", "").lowercase()
            val mood = when (moodRaw) {
                "candid", "candid_direct" -> "candid"
                "firm", "firm_warning" -> "firm"
                "warm", "validating" -> "warm"
                "calm", "neutral" -> "calm"
                else -> ""
            }
            var message = json.optString("message", "")
                .ifEmpty { json.optString("answer", "") }
                .ifEmpty { json.optString("text", "") }
            if (message.contains("\"candidates\"") || message.contains("\"finishReason\"") || message.contains("\"error\"")) {
                message = ""
            }
            AgentStep(
                thought = json.optString("thought", ""),
                mood = mood,
                status = status,
                tool = tool,
                query = json.optString("query", ""),
                url = json.optString("url", ""),
                key = json.optString("key", ""),
                value = json.optString("value", ""),
                text = json.optString("text", ""),
                mode = json.optString("mode", ""),
                title = json.optString("title", ""),
                message = message,
            )
        }.getOrNull()
    }

    /**
     * Autonomous agent Think-Act-Observe loop.
     * The model loops by default to execute tasks and interactively observe results.
     * Only the AI invokes termination by setting status to "DONE".
     */
    private suspend fun askGemini(
        userText: String,
        tone: String,
        mode: String,
        extraContext: String = "",
        attachScreenshot: Boolean = false,
    ): String {
        val container = (application as CompanionApp).container
        val history = runCatching { container.chatHistory.buildConversationContext() }.getOrDefault("")
        runCatching { container.chatHistory.addUser(userText) }
        val memory = runCatching { container.memoryContext.build() }.getOrDefault("")
        val userName = container.learnedMemoryManager.get("user_name")
            ?: runCatching { container.memoryStore.factValue("user_name") }.getOrNull()
        val identityRule = if (userName != null) {
            "Their name is $userName. Greet them simply and naturally (e.g. 'Hey $userName!'). Never add cheesy filler like 'just hanging out with you'."
        } else {
            "You do not know their name yet — never invent or guess one."
        }
        val P = com.shiina.mobile.decision.ShiinaPrompts
        val senses = runCatching { container.deviceSenses.snapshot() }.getOrDefault("{}")
        val timeInfo = P.timeLine(this)
        val learnedMemory = runCatching { container.learnedMemoryManager.readMemory() }.getOrDefault("")

        fun buildBasePrompt(includeTools: Boolean = false): String = buildString {
            appendLine("# Persona & Instructions")
            appendLine(P.GLOBAL_RULES)
            appendLine(P.moodPrompt(P.moodForTone(tone)))
            appendLine(P.TALK_DRIVE)
            appendLine(identityRule)
            appendLine()

            if (includeTools) {
                appendLine("# Available Tools & Protocol")
                appendLine(P.TOOL_SPEC)
                appendLine()
            }

            appendLine("# Live Context & Device Senses")
            appendLine("- $timeInfo")
            appendLine("- Senses: $senses")
            appendLine()

            if (learnedMemory.isNotBlank()) {
                appendLine("# Learned Memory (Persistent Knowledge File)")
                appendLine(learnedMemory.trim())
                appendLine()
            }

            if (memory.isNotBlank()) {
                appendLine("# Episodic Memory & Context")
                appendLine(memory.trim())
                appendLine()
            }

            if (history.isNotBlank()) {
                appendLine("# Conversation History")
                appendLine(history.trim())
                appendLine()
            }

            appendLine("# Current User Message")
            appendLine("User: $userText")
        }

        if (extraContext.isNotBlank() || attachScreenshot) {
            val fastPrompt = buildString {
                append(buildBasePrompt(includeTools = false))
                appendLine()
                appendLine()
                appendLine("# Additional Context")
                appendLine(extraContext.trim())
            }
            return postText(fastPrompt, attachScreenshot)
        }

        var prompt = buildBasePrompt(includeTools = true)
        val steps = mutableListOf<String>()
        var lastSig = ""
        var answer = ""
        var calls = 0
        var attachShot = false
        val MAX_AGENT_STEPS = 8

        while (calls < MAX_AGENT_STEPS) {
            val raw = postText(prompt, attachShot)
            attachShot = false
            val step = parseAgentStep(raw)
            if (step != null) {
                if (step.mood.isNotBlank()) {
                    lastTone = step.mood
                    AppDebugServer.log("TALK_MOOD", "Shiina dynamically set mood to: ${step.mood}")
                }
                AppDebugServer.log(
                    "GEMINI_TALK_PARSED",
                    "Agent step #${calls + 1}: mood=${step.mood}, status=${step.status}, tool=${step.tool}, thought=\"${step.thought.take(80)}\", message=\"${step.message.take(80)}\"",
                )
            }
            if (step == null) {
                // Direct plain text response from the model
                answer = raw.trim().take(512)
                break
            }

            // Capture message from the step
            if (step.message.isNotBlank()) {
                answer = step.message.trim()
                // Only push live interactive progress to overlay for long-running tools (search, screenshot).
                // Avoid pushing intermediate status for fast local tools (LEARN, REMEMBER, SET_MODE) to prevent duplicate/repetitive messages!
                if (step.status.equals("CONTINUE", ignoreCase = true) && step.tool in setOf("SEARCH_WEB", "READ_URL", "TAKE_SCREENSHOT")) {
                    pushTextToOverlay(step.message.trim())
                    AppDebugServer.log("TALK_INTERACTION", "Agent live status: ${step.message.trim()}")
                }
            }

            // Only the AI ends the loop by invoking DONE (or tool is NONE and status != CONTINUE)
            val isDone = step.status.equals("DONE", ignoreCase = true) ||
                (step.tool == "NONE" && !step.status.equals("CONTINUE", ignoreCase = true))

            if (isDone) {
                // Execute tool if one was specified on the final step (e.g. LEARN, REMEMBER, SET_MODE, COMPLETE_GOAL)
                if (step.tool != "NONE") {
                    val result = runTool(step)
                    runCatching { container.toolTracker.record(step.tool.lowercase(), result.first.isNotEmpty()) }
                    AppDebugServer.log("TALK_TOOL_FINAL", "Final step tool ${step.tool} -> ${result.first.take(80)}")
                }
                if (answer.isEmpty() || (answer.startsWith("{") && answer.contains("\"candidates\""))) {
                    answer = step.message.ifBlank {
                        if (raw.startsWith("{") && raw.contains("\"candidates\"")) "" else raw
                    }.trim().take(512)
                }
                AppDebugServer.log("TALK_AGENT", "Agent invoked completion (DONE) at step #${calls + 1}")
                break
            }

            // Repetition detection
            val sig = step.tool + "|" + (step.query + step.url + step.key + step.mode + step.title).take(120)
            if (sig == lastSig && step.tool != "NONE") {
                AppDebugServer.log("TALK_LOOP", "Repetition detected for $sig — breaking loop")
                break
            }
            lastSig = sig
            calls++

            val result = runTool(step)
            if (result.second) attachShot = true
            runCatching { container.toolTracker.record(step.tool.lowercase(), result.first.isNotEmpty()) }
            steps += "${step.tool}: ${result.first.take(100).ifEmpty { "empty" }}"
            AppDebugServer.log(
                "DEBUG_PANEL", "Tool ${step.tool} -> ${result.first.take(80).ifEmpty { "(empty)" }}",
            )
            val feed = result.first.ifEmpty { "no results" }

            prompt = buildString {
                append(buildBasePrompt(includeTools = true))
                appendLine()
                appendLine()
                appendLine("# Agent Execution State")
                appendLine("- Current step: #${calls} of max $MAX_AGENT_STEPS")
                appendLine("- Steps taken so far: ${steps.joinToString("; ")}")
                if (step.thought.isNotBlank()) {
                    appendLine("- Your previous thought: ${step.thought}")
                }
                appendLine("- Observation from ${step.tool}: $feed")
                appendLine()
                appendLine("Instructions: The action '${step.tool}' completed. Analyze the observation. Set status to 'DONE' to deliver your final conversational response, or 'CONTINUE' only if another action is strictly needed. Do NOT repeat previous status messages or action confirmations.")
            }
        }
        if (answer.isEmpty() || (answer.startsWith("{") && answer.contains("\"candidates\""))) {
            val finalPrompt = buildString {
                append(buildBasePrompt(includeTools = false))
                appendLine()
                appendLine()
                appendLine("# Agent Execution Summary")
                appendLine("- Steps taken: ${steps.joinToString("; ").ifEmpty { "none" }}")
                appendLine()
                appendLine("Answer now in a warm, friendly, and natural conversational tone (no JSON, no meta explanations).")
            }
            answer = postText(finalPrompt, attachShot)
        }
        if (answer.isEmpty() || (answer.startsWith("{") && answer.contains("\"candidates\""))) {
            answer = "Hmm, I lost my train of thought for a second. What's on your mind?"
        }
        return answer.trim().take(512).ifEmpty { "(empty reply)" }
    }

    /** Executes one parsed agent step tool -> (result text, needs screenshot attach). */
    private suspend fun runTool(step: AgentStep): Pair<String, Boolean> {
        val container = (application as CompanionApp).container
        return when (step.tool) {
            "LEARN", "REMEMBER" -> {
                var k = step.key
                var v = step.value
                if (k.isEmpty() || v.isEmpty()) {
                    val combined = "${step.thought} ${step.query} ${step.text}"
                    val nameMatch = Regex("(?:name is|called|user is|named) ([a-zA-Z]+)", RegexOption.IGNORE_CASE)
                        .find(combined)
                    if (nameMatch != null) {
                        k = "user_name"
                        v = nameMatch.groupValues[1].replaceFirstChar { c -> c.uppercase() }
                    }
                }
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    val msg = runCatching {
                        container.learnedMemoryManager.learn(k, v, "ai_learned")
                    }.getOrDefault("learned $k")
                    msg to false
                } else "learn requires key and value" to false
            }
            "SEARCH_WEB" ->
                runCatching { container.webSearch.search(step.query.ifBlank { "latest" }) }
                    .getOrDefault("") to false
            "READ_URL" ->
                runCatching { container.pageReader.read(step.url) }.getOrDefault("") to false
            "TAKE_SCREENSHOT" -> {
                val f = runCatching { container.screenshotTaker.capture("talk") }.getOrNull()
                if (f != null) "screenshot captured" to true else "" to false
            }
            "CHECK_GOALS" ->
                runCatching { container.actionExecutor.goalReport() }.getOrDefault("no goals") to false
            "SET_REMINDER" -> {
                val msg = runCatching {
                    container.reminderScheduler.parseAndSchedule(
                        step.text.ifEmpty { step.query },
                    ).orEmpty()
                }.getOrDefault("")
                (msg.ifEmpty { "no time found in the reminder — ask when" }) to false
            }
            "COMPLETE_GOAL" ->
                runCatching {
                    container.actionExecutor.completeGoalPublic(
                        step.title.ifEmpty { step.text.ifEmpty { step.query } },
                    )
                }.getOrDefault("complete_goal failed") to false
            "SET_MODE" -> {
                val m = step.mode.ifEmpty { step.query }.uppercase()
                val target = when (m) {
                    "WANDER" -> CharacterMode.WANDER
                    "VANISH", "HIDE" -> CharacterMode.VANISH
                    else -> CharacterMode.STAY
                }
                lastMode = target.name
                val i = Intent(this, CharacterOverlayService::class.java).apply {
                    action = if (target == CharacterMode.VANISH) CharacterOverlayService.ACTION_HIDE else CharacterOverlayService.ACTION_SHOW
                    putExtra(CharacterOverlayService.EXTRA_TONE, lastTone)
                    putExtra(CharacterOverlayService.EXTRA_MODE, target.name)
                }
                startForegroundService(i)
                "character mode set to ${target.name}" to false
            }
            else -> "" to false
        }
    }

    /** Single Gemini text call with optional vision attach. Plain text out. */
    private suspend fun postText(prompt: String, attachShot: Boolean): String {
        AppDebugServer.log(
            "GEMINI_TALK_REQUEST",
            "Talk prompt:\n$prompt" + if (attachShot) " [screenshot attached]" else "",
        )
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
        for (attempt in 1..2) {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$key")
                .post(
                    JSONObject()
                        .put(
                            "contents", org.json.JSONArray().put(
                                JSONObject().put("parts", parts),
                            ),
                        )
                        .toString()
                        .toRequestBody("application/json".toMediaType()),
                )
                .build()
            val raw = runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("gemini ${response.code}")
                    response.body?.string().orEmpty()
                }
            }.getOrDefault("")

            if (raw.isNotBlank()) {
                AppDebugServer.log("GEMINI_TALK_RESPONSE", "Talk raw response (attempt $attempt):\n$raw")
                val json = runCatching { JSONObject(raw) }.getOrNull()
                val candidate = json?.optJSONArray("candidates")?.optJSONObject(0)
                val finishReason = candidate?.optString("finishReason", "")
                val candidateParts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                val text = (0 until (candidateParts?.length() ?: 0))
                    .mapNotNull { candidateParts?.optJSONObject(it)?.optString("text") }
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()

                if (!text.isNullOrBlank()) {
                    return text.take(1024)
                }
                AppDebugServer.log("WARN", "Gemini returned empty text or finishReason=$finishReason on attempt $attempt")
            }
            if (attempt < 2) kotlinx.coroutines.delay(500)
        }

        return "{\"thought\": \"Response generation failed\", \"status\": \"DONE\", \"tool\": \"NONE\", \"message\": \"Hmm, I lost my train of thought for a second. What's on your mind?\"}"
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
