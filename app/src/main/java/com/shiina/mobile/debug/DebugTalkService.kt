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
import com.shiina.mobile.decision.ToolCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    @Volatile private var isGreetingInProgress: Boolean = false
    @Volatile private var lastGreetingTimestamp: Long = 0L

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
            delay(1500L)
            triggerBootGreeting()
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
                ACTION_BOOT_GREETING -> triggerBootGreeting()
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
                // LOOP-1: real goals + sleep feed the debug decision too.
                val goals = runCatching { container.database.goalDao().all() }.getOrDefault(emptyList())
                val bedMillis = runCatching { container.sleepReader.getLatestBedMillis() }.getOrNull() ?: 0L
                val decision = container.providerRegistry.decide(
                    DecisionSummary(
                        entertainmentMinutes = minutes,
                        entertainmentBaseline = baseline,
                        sleepBedMillis = bedMillis,
                        sleepBaselineMillis = 0L,
                        goalsOpen = goals.count { it.status == 0 },
                        goalsDone = goals.count { it.status == 1 },
                        goalsMissed = goals.count { it.status == 2 },
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
        // Notification RemoteInput reply, or adb-sent text via AdbTalkReceiver.
        // Audit U5: cap text so a paste bomb can't blow the prompt.
        val remote = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TALK)?.toString()?.trim()?.take(500).orEmpty()
        val text = if (remote.isNotEmpty()) remote else {
            intent.getStringExtra(EXTRA_ADB_TEXT)?.trim()?.take(500).orEmpty()
        }
        if (text.isEmpty()) return
        dbg("You: $text")
        val container = (application as CompanionApp).container
        scope.launch {
            runCatching { container.memoryOutcomes.onTalkReply() }
            // Dynamic Mood Balancing: user speech nudges the emotional vector
            // (praise/complaint keywords detected inside the engine).
            runCatching { container.moodEngine.applyUserText(text) }
        }
        container.userActivityTracker.recordActivity()

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
            val lastUserTurn = runCatching {
                container.chatHistory.allTurns().lastOrNull { it.role.equals("user", ignoreCase = true) }?.timestampMillis
            }.getOrNull() ?: 0L
            val hoursInactive = container.userActivityTracker.getHoursSinceLastActive(fallback = lastUserTurn)
            if (hoursInactive >= 10.0) {
                runCatching {
                    container.moodEngine.applyEvent(
                        com.shiina.mobile.character.MoodEngine.Event.NEGLECT,
                        "neglected ${hoursInactive.toInt()}h",
                    )
                }
                lastTone = runCatching { container.moodEngine.currentMood() }.getOrDefault("pouty")
                dbg("User was inactive for ${"%.1f".format(hoursInactive)}h — mood now $lastTone")
            }

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
            val reply = runCatching {
                askGemini(
                    userText = text,
                    tone = lastTone,
                    mode = lastMode,
                    extraContext = extra,
                    attachScreenshot = attachShot,
                    hoursInactive = hoursInactive,
                )
            }.getOrElse { e ->
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
            scheduleBackgroundConsolidation()
        }
    }

    private var consolidationJob: Job? = null

    /**
     * Debounced background memory consolidation.
     * When conversation pauses for 20 seconds, extracts durable facts without
     * interfering with the user's active conversation turns.
     */
    private fun scheduleBackgroundConsolidation() {
        consolidationJob?.cancel()
        consolidationJob = scope.launch {
            delay(20_000L)
            val container = (application as CompanionApp).container
            runCatching {
                container.memoryConsolidator.consolidate()
            }.onFailure { e ->
                AppDebugServer.log("ERROR", "Background memory consolidation failed: ${e.message}")
            }
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
        val elementId: Int = -1,
        val x: Float = -1f,
        val y: Float = -1f,
        val startX: Float = -1f,
        val startY: Float = -1f,
        val endX: Float = -1f,
        val endY: Float = -1f,
        val direction: String = "",
        val message: String = "",
    )

    private val KNOWN_TOOLS = setOf(
        "GET_TOOLSET",
        "SEARCH_MUSIC", "PLAY_MUSIC", "MEDIA_CONTROL", "VOLUME_CONTROL",
        "OPEN_APP", "SEARCH_APP", "LIST_APPS", "GET_DEVICE_STATE",
        "SEARCH_WEB", "READ_URL", "TAKE_SCREENSHOT", "INSPECT_SCREEN", "REMEMBER",
        "CHECK_GOALS", "LOG_GOAL", "SET_REMINDER", "COMPLETE_GOAL",
        "SET_MODE", "DEVICE_ACTION",
        "TAP_SCREEN", "SWIPE_SCREEN", "INPUT_TEXT", "PRESS_KEY",
        "NONE",
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
                "pouty", "sulky" -> "pouty"
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
            val args = json.optJSONObject("tool_args") ?: json
            val query = args.optString("query", "")
                .ifEmpty { args.optString("song", "") }
                .ifEmpty { args.optString("track", "") }
                .ifEmpty { args.optString("title", "") }
            val level = args.optInt("level", -1)
            val elementId = args.optInt("element_id", -1).let { if (it > 0) it else json.optInt("element_id", -1) }
            val toolset = args.optString("toolset", "")
                .ifEmpty { json.optString("toolset", "") }
                .ifEmpty { args.optString("name", "") }
            val x = args.optDouble("x", -1.0).toFloat()
            val y = args.optDouble("y", -1.0).toFloat()
            val startX = args.optDouble("startX", -1.0).toFloat()
            val startY = args.optDouble("startY", -1.0).toFloat()
            val endX = args.optDouble("endX", -1.0).toFloat()
            val endY = args.optDouble("endY", -1.0).toFloat()
            val direction = args.optString("direction", "")
            AgentStep(
                thought = json.optString("thought", ""),
                mood = mood,
                status = status,
                tool = tool,
                toolset = toolset,
                query = query,
                url = args.optString("url", ""),
                key = args.optString("key", ""),
                value = args.optString("value", ""),
                text = args.optString("text", ""),
                mode = args.optString("mode", ""),
                title = args.optString("title", ""),
                action = args.optString("action", ""),
                app = args.optString("app", ""),
                player = args.optString("player", ""),
                filter = args.optString("filter", ""),
                level = level,
                elementId = elementId,
                x = x,
                y = y,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                direction = direction,
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
        isSystemTrigger: Boolean = false,
        hoursInactive: Double = 0.0,
    ): String {
        val container = (application as CompanionApp).container
        val history = runCatching { container.chatHistory.buildConversationContext() }.getOrDefault("")
        if (!isSystemTrigger) {
            runCatching { container.chatHistory.addUser(userText) }
        }
        val memory = runCatching { container.memoryContext.build() }.getOrDefault("")
        val userName = container.learnedMemoryManager.get("user_name")
            ?: runCatching { container.memoryStore.factValue("user_name") }.getOrNull()
        val identityRule = if (userName != null) {
            "Their name is $userName. Talk to them naturally like a close friend who knows them well. Never add cheesy companion filler like 'just hanging out with you'."
        } else {
            "You do not know their name yet — never invent or guess one."
        }
        val P = com.shiina.mobile.decision.ShiinaPrompts
        val senses = runCatching { container.deviceSenses.snapshot() }.getOrDefault("{}")
        val timeInfo = P.timeLine(this)
        val learnedMemory = runCatching { container.learnedMemoryManager.readMemory() }.getOrDefault("")
        // Suspend call hoisted out of the non-suspend buildBasePrompt closure.
        val moodBlock = runCatching { container.moodEngine.promptBlock() }.getOrNull().orEmpty()

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

            val screenRes = container.screenMetrics.toString()
            appendLine("# Live Context & Device Senses")
            appendLine("- $timeInfo")
            appendLine("- Senses: $senses")
            appendLine("- Screen Resolution: $screenRes (normalized 0..1000: x: 0..1000, y: 0..1000)")
            if (moodBlock.isNotBlank()) appendLine("- $moodBlock")
            if (hoursInactive >= 10.0 && !isSystemTrigger) {
                appendLine("- User Absence Alert: The user has not opened the app or spoken to you for ${hoursInactive.toInt()} hours! You are in a POUTY bad mood because they neglected you. Sulk, pout, or complain about being abandoned before warming back up.")
            }
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

            val nowFmt = java.text.SimpleDateFormat("yyyy-MM-dd h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
            if (isSystemTrigger) {
                appendLine("# Event Trigger")
                appendLine("[$nowFmt] $userText")
            } else {
                appendLine("# Current User Message")
                appendLine("[$nowFmt] User: $userText")
            }
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

        // Initial screenshot capture if user explicitly asked about the screen
        var shouldAttachShot = attachScreenshot
        if (!shouldAttachShot && isScreenshotAsk(userText)) {
            val f = runCatching { container.screenshotTaker.capture("talk") }.getOrNull()
            if (f != null) shouldAttachShot = true
        }

        var prompt = buildBasePrompt(includeTools = true)
        val steps = mutableListOf<String>()
        var answer = ""
        var calls = 0
        val MAX_AGENT_STEPS = 25

        while (calls < MAX_AGENT_STEPS) {
            val raw = postText(prompt, attachShot = shouldAttachShot, structuredSchema = true)
            shouldAttachShot = false
            val step = parseAgentStep(raw)

            if (step != null) {
                if (step.mood.isNotBlank()) {
                    // Dynamic Mood Balancing: the model's chosen mood nudges the
                    // persistent vector 35% instead of overwriting her state.
                    runCatching { container.moodEngine.applyModelExpression(step.mood) }
                    lastTone = runCatching { container.moodEngine.currentMood() }.getOrDefault(step.mood)
                    AppDebugServer.log("TALK_MOOD", "Model expressed ${step.mood} -> mood now $lastTone")
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

            // Only accept final answer when tool is NONE and status is DONE (or status != CONTINUE).
            // If a tool was invoked, the loop MUST continue so the agent observes the outcome receipt!
            val isDone = (step.tool == "NONE" || step.tool.isBlank()) &&
                (step.status.equals("DONE", ignoreCase = true) || !step.status.equals("CONTINUE", ignoreCase = true))

            if (isDone) {
                var finalMsg = step.message.trim()
                if (finalMsg.isBlank() || finalMsg.startsWith("{") || finalMsg.startsWith("```") || finalMsg.contains("\"thought\":")) {
                    val extracted = parseAgentStep(raw)?.message?.trim().orEmpty()
                    if (extracted.isNotBlank() && !extracted.startsWith("{") && !extracted.contains("\"thought\":")) {
                        finalMsg = extracted
                    } else {
                        // Guaranteed to talk at the final step!
                        AppDebugServer.log("TALK_AGENT", "Final step had no conversational message (or was JSON); synthesizing natural speech...")
                        val finalPrompt = buildString {
                            append(buildBasePrompt(includeTools = false))
                            appendLine()
                            appendLine()
                            if (steps.isNotEmpty()) {
                                appendLine("# Agent Execution Summary")
                                appendLine("- Steps taken: ${steps.joinToString("; ")}")
                                appendLine()
                            }
                            appendLine("The task is completed. Answer the user now in an authentic, natural conversational tone as Shiina (1-2 sentences, plain text ONLY, absolutely NO JSON).")
                        }
                        finalMsg = postText(finalPrompt, attachShot = false).trim()
                    }
                }
                answer = finalMsg.trim().take(512)
                AppDebugServer.log("TALK_AGENT", "Agent invoked completion (DONE) at step #${calls + 1}")
                break
            }

            // Intermediate step: if message provided, push it; otherwise execute silently
            if (step.message.isNotBlank()) {
                val progressMsg = step.message.trim()
                pushTextToOverlay(progressMsg)
                AppDebugServer.log("TALK_INTERACTION", "Agent progress status: $progressMsg")
            } else {
                AppDebugServer.log("TALK_INTERACTION", "Agent executing step #${calls + 1} silently (${step.tool})")
            }

            calls++

            val result = runTool(step)
            // Wait for UI animation/transition: allow extra settle time for app launches and screen taps
            val waitMs = when (step.tool) {
                "OPEN_APP" -> 2000L
                "TAP_SCREEN" -> 1500L
                else -> 1000L
            }
            delay(waitMs)
            if (result.second) {
                shouldAttachShot = true
            }
            runCatching { container.toolTracker.record(step.tool.lowercase(), result.first.isNotEmpty()) }
            steps += "${step.tool}: ${result.first.take(150).ifEmpty { "empty" }}"
            AppDebugServer.log(
                "DEBUG_PANEL", "Tool ${step.tool} -> ${result.first.take(120).ifEmpty { "(empty)" }}",
            )
            val feed = result.first.ifEmpty { "no results" }

            prompt = buildString {
                append(buildBasePrompt(includeTools = true))
                appendLine()
                appendLine()
                appendLine("# Agent Execution State")
                appendLine("- Current step: #${calls}")
                appendLine("- Primary Goal: \"$userText\"")
                appendLine("- Steps taken so far: ${steps.joinToString("; ")}")
                if (step.thought.isNotBlank()) {
                    appendLine("- Your previous thought: ${step.thought}")
                }
                appendLine("- Observation from ${step.tool}: $feed")
                if (step.tool == "TAKE_SCREENSHOT" && result.second) {
                    appendLine("- Visual Verification: The screenshot you requested is attached as an image. Inspect the screen carefully to verify if \"$userText\" has been completed.")
                }
            }
        }
        withContext(Dispatchers.Main) { refresh() }
        if (answer.isBlank() || answer.startsWith("{") || answer.startsWith("```") || answer.contains("\"thought\":") || answer.contains("\"candidates\"")) {
            val extracted = parseAgentStep(answer)?.message?.trim().orEmpty()
            if (extracted.isNotBlank() && !extracted.startsWith("{") && !extracted.contains("\"thought\":")) {
                answer = extracted
            } else {
                val finalPrompt = buildString {
                    append(buildBasePrompt(includeTools = false))
                    appendLine()
                    appendLine()
                    if (steps.isNotEmpty()) {
                        appendLine("# Agent Execution Summary")
                        appendLine("- Steps taken: ${steps.joinToString("; ").ifEmpty { "none" }}")
                        appendLine()
                    }
                    appendLine("Answer now in a warm, friendly, and natural conversational tone as Shiina (plain text ONLY, 1-2 sentences, absolutely no JSON, no meta explanations).")
                }
                answer = postText(finalPrompt, attachShot = false).trim()
                if (answer.startsWith("{") || answer.startsWith("```") || answer.contains("\"thought\":")) {
                    answer = parseAgentStep(answer)?.message?.trim().orEmpty()
                }
            }
        }
        if (answer.isBlank() || answer.startsWith("{") || answer.startsWith("```") || answer.contains("\"thought\":")) {
            answer = "Hmm, I lost my train of thought for a second. What's on your mind?"
        }
        return answer.trim().take(512).ifEmpty { "(empty reply)" }
    }

    /** Executes one parsed agent step tool -> (result text, needs screenshot attach). */
    private suspend fun runTool(step: AgentStep): Pair<String, Boolean> {
        val container = (application as CompanionApp).container
        return when (step.tool) {
            "GET_TOOLSET" -> {
                val name = step.toolset.ifBlank { step.query.ifBlank { step.text } }
                ToolCatalog.getToolset(name) to false
            }
            "REMEMBER" -> {
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
                        container.learnedMemoryManager.learn(k, v, "remember_tool")
                    }.getOrDefault("remembered $k")
                    msg to false
                } else "remember requires key and value" to false
            }
            "SEARCH_WEB" ->
                runCatching { container.webSearch.search(step.query.ifBlank { "latest" }) }
                    .getOrDefault("") to false
            "READ_URL" ->
                runCatching { container.pageReader.read(step.url) }.getOrDefault("") to false
            "TAKE_SCREENSHOT" -> {
                val f = runCatching { container.screenshotTaker.capture("talk") }.getOrNull()
                if (f != null) "Screenshot captured successfully. The screen image is attached for your inspection." to true
                else "Screenshot capture failed (permission not granted or display busy)." to false
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
            "SEARCH_MUSIC" -> {
                val q = step.query.ifEmpty { step.text }
                val receipt = container.deviceActionController.searchMusic(q)
                receipt to false
            }
            "PLAY_MUSIC" -> {
                val q = step.query.ifEmpty { step.text.ifEmpty { step.action } }
                val receipt = container.deviceActionController.playSong(q, step.player)
                receipt to false
            }
            "VOLUME_CONTROL" -> {
                val act = step.action.ifEmpty { step.query.ifEmpty { step.text } }
                val receipt = container.deviceActionController.volumeControl(act, step.level)
                receipt to false
            }
            "DEVICE_ACTION" -> {
                val act = step.action.ifEmpty { step.query.ifEmpty { step.text } }
                val receipt = container.deviceActionController.volumeControl(act, step.level)
                receipt to false
            }
            "MEDIA_CONTROL" -> {
                val act = step.action.ifEmpty { step.text }
                val q = step.query
                val receipt = if (q.isNotBlank() && (act.isBlank() || act.equals("play", ignoreCase = true) || act.equals("play_song", ignoreCase = true))) {
                    container.deviceActionController.playSong(q, step.player)
                } else if (act.startsWith("play ", ignoreCase = true)) {
                    container.deviceActionController.playSong(act.removePrefix("play ").trim(), step.player)
                } else {
                    container.deviceActionController.controlMedia(act.ifEmpty { q })
                }
                receipt to false
            }
            "SEARCH_APP" -> {
                val app = step.app.ifEmpty { "youtube" }
                val q = step.query.ifEmpty { step.text }
                val receipt = container.deviceActionController.searchApp(app, q)
                receipt to false
            }
            "LIST_APPS" -> {
                val f = step.filter.ifEmpty { step.query }
                val receipt = container.deviceActionController.listApps(f)
                receipt to false
            }
            "GET_DEVICE_STATE" -> {
                val receipt = container.deviceActionController.getDeviceState()
                receipt to false
            }
            "OPEN_APP" -> {
                val app = step.app.ifEmpty { step.query.ifEmpty { step.text } }
                val receipt = container.deviceActionController.openApp(app)
                receipt to false
            }
            "LOG_GOAL" -> {
                val t = step.title.ifEmpty { step.text.ifEmpty { step.query } }
                if (t.isNotBlank()) {
                    runCatching {
                        container.actionExecutor.execute("log_goal", t)
                    }
                    "goal logged: $t" to false
                } else "log_goal requires a goal title" to false
            }
            "INSPECT_SCREEN" -> {
                val receipt = container.deviceActionController.getScreenElementsSummary()
                receipt to true
            }
            "TAP_SCREEN" -> {
                val receipt = container.deviceActionController.tapScreen(step.x, step.y, step.text, step.elementId)
                receipt to false
            }
            "SWIPE_SCREEN" -> {
                val receipt = container.deviceActionController.swipeScreen(step.startX, step.startY, step.endX, step.endY, step.direction)
                receipt to false
            }
            "INPUT_TEXT" -> {
                val receipt = container.deviceActionController.inputText(step.text.ifEmpty { step.query })
                receipt to false
            }
            "PRESS_KEY" -> {
                val receipt = container.deviceActionController.pressKey(step.action.ifEmpty { step.query })
                receipt to false
            }
            else -> "" to false
        }
    }

    /** Single Gemini text call with optional vision attach and optional JSON schema enforcement. */
    private suspend fun postText(prompt: String, attachShot: Boolean, structuredSchema: Boolean = false): String {
        AppDebugServer.log(
            "GEMINI_TALK_REQUEST",
            "Talk prompt:\n$prompt" + if (attachShot) " [screenshot attached]" else "",
        )
        val container = (application as CompanionApp).container
        val keyPool = container.geminiKeyPool
        val totalKeys = container.keyStore.getKeys("gemini").size.coerceAtLeast(1)
        val maxAttempts = (totalKeys * 2).coerceAtMost(4)

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

        val requestBody = JSONObject()
            .put("contents", org.json.JSONArray().put(JSONObject().put("parts", parts)))

        if (structuredSchema) {
            val toolEnum = org.json.JSONArray()
            for (t in KNOWN_TOOLS) {
                toolEnum.put(t)
            }
            val moodEnum = org.json.JSONArray().put("calm").put("candid").put("warm").put("firm").put("pouty")
            val statusEnum = org.json.JSONArray().put("CONTINUE").put("DONE")

            val toolArgsSchema = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put("toolset", JSONObject().put("type", "STRING"))
                        .put("query", JSONObject().put("type", "STRING"))
                        .put("action", JSONObject().put("type", "STRING"))
                        .put("app", JSONObject().put("type", "STRING"))
                        .put("filter", JSONObject().put("type", "STRING"))
                        .put("player", JSONObject().put("type", "STRING"))
                        .put("level", JSONObject().put("type", "INTEGER"))
                        .put("title", JSONObject().put("type", "STRING"))
                        .put("url", JSONObject().put("type", "STRING"))
                        .put("mode", JSONObject().put("type", "STRING"))
                        .put("element_id", JSONObject().put("type", "INTEGER"))
                        .put("x", JSONObject().put("type", "NUMBER"))
                        .put("y", JSONObject().put("type", "NUMBER"))
                        .put("startX", JSONObject().put("type", "NUMBER"))
                        .put("startY", JSONObject().put("type", "NUMBER"))
                        .put("endX", JSONObject().put("type", "NUMBER"))
                        .put("endY", JSONObject().put("type", "NUMBER"))
                        .put("text", JSONObject().put("type", "STRING"))
                        .put("direction", JSONObject().put("type", "STRING")),
                )

            val stepSchema = JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put("thought", JSONObject().put("type", "STRING"))
                        .put("mood", JSONObject().put("type", "STRING").put("enum", moodEnum))
                        .put("status", JSONObject().put("type", "STRING").put("enum", statusEnum))
                        .put("tool", JSONObject().put("type", "STRING").put("enum", toolEnum))
                        .put("tool_args", toolArgsSchema)
                        .put(
                            "message",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "Natural conversational response spoken to the user. MANDATORY when status is DONE. Only omit or leave empty \"\" when executing intermediate tools silently."),
                        ),
                )
                .put(
                    "required",
                    org.json.JSONArray()
                        .put("thought")
                        .put("status")
                        .put("tool"),
                )

            requestBody.put(
                "generationConfig",
                JSONObject()
                    .put("response_mime_type", "application/json")
                    .put("response_schema", stepSchema),
            )
        }

        val configuredModel = runCatching { container.settingsRepository.getGeminiModel() }
            .getOrDefault("gemini-3.5-flash-lite")
        val alternateModel = if (configuredModel == "gemini-3.5-flash-lite") {
            "gemini-3.1-flash-lite"
        } else {
            "gemini-3.5-flash-lite"
        }

        var currentModel = configuredModel
        var fallbackTriggered = false

        for (attempt in 1..maxAttempts) {
            val key = keyPool.next()
                ?: container.keyStore.getKeys("gemini").firstOrNull()
                ?: throw IllegalStateException("no gemini keys configured")

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$currentModel:generateContent?key=$key")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val raw = runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code == 429) {
                            keyPool.reportFailure(key, 60_000L)
                            AppDebugServer.log("WARN", "Gemini key ...${key.takeLast(4)} hit 429 rate limit on $currentModel, rotating key in pool")
                            if (!fallbackTriggered) {
                                currentModel = alternateModel
                                fallbackTriggered = true
                                AppDebugServer.log("WARN", "Failing over to alternate model: $currentModel (separate quota pool)")
                            }
                        }
                        throw IllegalStateException("gemini ${response.code}")
                    }
                    response.body?.string().orEmpty()
                }
            }.getOrDefault("")

            if (raw.isNotBlank()) {
                AppDebugServer.log("GEMINI_TALK_RESPONSE", "Talk raw response (attempt $attempt, model $currentModel, key ...${key.takeLast(4)}):\n$raw")
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
                AppDebugServer.log("WARN", "Gemini returned empty text or finishReason=$finishReason on attempt $attempt ($currentModel)")
            }
            if (attempt < maxAttempts) kotlinx.coroutines.delay(300)
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

    private fun triggerBootGreeting() {
        scope.launch {
            if (isGreetingInProgress) {
                dbg("Boot greeting already in progress — skipping")
                return@launch
            }
            val now = System.currentTimeMillis()
            if (now - lastGreetingTimestamp < 10 * 60 * 1000L) {
                dbg("Boot greeting skipped: last greeting was within 10m")
                return@launch
            }
            val container = (application as CompanionApp).container
            val lastTurn = runCatching { container.chatHistory.allTurns().lastOrNull() }.getOrNull()
            if (lastTurn != null && now - lastTurn.timestampMillis < 10 * 60 * 1000L) {
                dbg("Boot greeting skipped: recent conversation turn within 10m (${lastTurn.text.take(30)})")
                return@launch
            }

            isGreetingInProgress = true
            try {
                val lastUserTurn = runCatching {
                    container.chatHistory.allTurns().lastOrNull { it.role.equals("user", ignoreCase = true) }?.timestampMillis
                }.getOrNull() ?: 0L
                val hoursInactive = container.userActivityTracker.getHoursSinceLastActive(fallback = lastUserTurn)

                if (hoursInactive >= 10.0) {
                    lastTone = "pouty"
                } else {
                    val recentTone = runCatching { container.memoryEpisodeDao.recent(1).firstOrNull()?.tone }.getOrNull()
                    if (!recentTone.isNullOrBlank()) {
                        lastTone = com.shiina.mobile.decision.ShiinaPrompts.moodForTone(recentTone)
                    }
                }

                dbg("Triggering boot greeting with mood: $lastTone (inactive: ${"%.1f".format(hoursInactive)}h)")
                val bootPrompt = com.shiina.mobile.decision.ShiinaPrompts.bootGreetingPrompt(lastTone, hoursInactive)

                val reply = runCatching {
                    askGemini(
                        userText = bootPrompt,
                        tone = lastTone,
                        mode = lastMode,
                        extraContext = "",
                        attachScreenshot = false,
                        isSystemTrigger = true,
                        hoursInactive = hoursInactive,
                    )
                }.getOrElse { e ->
                    AppDebugServer.log("ERROR", "Boot greeting failed: ${e.message}")
                    ""
                }

                if (reply.isNotBlank() && !reply.startsWith("Gemini unreachable")) {
                    lastGreetingTimestamp = System.currentTimeMillis()
                    lastReply = reply
                    runCatching { container.chatHistory.addShiina(reply) }
                    dbg("Shiina boot greeting ($lastTone): $reply")
                    pushTextToOverlay(reply)
                    runCatching {
                        container.memoryEpisodeDao.insert(
                            com.shiina.mobile.data.db.MemoryEpisode(
                                timestampMillis = System.currentTimeMillis(),
                                source = "boot_greeting",
                                entertainmentMinutes = 0,
                                entertainmentBaseline = 0.0,
                                goalsOpen = 0,
                                goalsDone = 0,
                                goalsMissed = 0,
                                tone = lastTone,
                                interrupt = false,
                                action = "NONE",
                                messageHash = reply.hashCode(),
                            ),
                        )
                    }
                    withContext(Dispatchers.Main) { refresh() }
                } else if (reply.startsWith("Gemini unreachable")) {
                    AppDebugServer.log("TALK_BOOT", "Boot greeting suppressed (Gemini unreachable: $reply)")
                }
            } finally {
                isGreetingInProgress = false
            }
        }
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
        const val ACTION_BOOT_GREETING = "com.shiina.mobile.debug.BOOT_GREETING"
        const val KEY_TALK = "key_talk"
        const val EXTRA_ADB_TEXT = "adb_text"
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
