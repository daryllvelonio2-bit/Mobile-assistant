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
import com.shiina.mobile.decision.AgentEngine
import com.shiina.mobile.decision.DecisionSummary
import com.shiina.mobile.decision.ShiinaPrompts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Foreground Service hosting the Shiina debug talk notification panel
 * and orchestrating conversation/command inputs with the AgentEngine.
 */
class DebugTalkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container by lazy { (application as CompanionApp).container }
    private val agentEngine by lazy { AgentEngine(this, container) }

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
            val recentTone = runCatching { container.memoryEpisodeDao.recent(1).firstOrNull()?.tone }.getOrNull()
            if (!recentTone.isNullOrBlank()) {
                lastTone = ShiinaPrompts.moodForTone(recentTone)
                withContext(Dispatchers.Main) { refresh() }
            }
            delay(1500L)
            triggerBootGreeting()
        }

        // Keep notification updated with busy/progress states
        scope.launch {
            ChatBus.busy.collectLatest {
                withContext(Dispatchers.Main) { refresh() }
            }
        }

        // Process-wide Voice TTS playback (Backlog B5)
        // Prewarm clone voice at startup so the first reply speaks without a model-load pause.
        runCatching { container.voiceSpeaker.prewarmNeural() }
        scope.launch {
            ChatBus.spokenUtterance.collectLatest { utterance ->
                if (utterance != null && utterance.text.isNotBlank()) {
                    val enabled = runCatching { container.settingsRepository.voiceTtsEnabled.first() }.getOrDefault(true)
                    if (enabled) {
                        container.voiceSpeaker.speak(
                            text = utterance.text,
                            mood = utterance.mood,
                            isLateNight = utterance.isLateNight,
                        )
                    }
                }
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
                ACTION_STOP -> {
                    ChatBus.requestStop()
                    dbg("Stop requested via ACTION_STOP")
                    refresh()
                }
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

        runCatching { refresh() }
        return START_STICKY
    }

    private fun dbg(msg: String) {
        if (logsEnabled) AppDebugServer.log("DEBUG_PANEL", msg)
    }

    private fun renderLatest() {
        scope.launch {
            runCatching {
                val minutes = withContext(Dispatchers.IO) {
                    container.usageReader.getTodayEntertainmentMinutes()
                }
                val baseline = container.baselineUpdater.getEntertainmentBaseline()
                dbg("Usage minutes: $minutes, baseline: $baseline")
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
                dbg("Decision: tone=${decision.tone}, intent=${decision.intent}, action=${decision.action}")
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
        }
    }

    private fun hideOverlay() {
        runCatching {
            val i = Intent(this, CharacterOverlayService::class.java).apply {
                action = CharacterOverlayService.ACTION_HIDE
            }
            startForegroundService(i)
        }
    }

    private fun handleTalk(intent: Intent) {
        val remote = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TALK)?.toString()?.trim()?.take(500).orEmpty()
        val text = if (remote.isNotEmpty()) remote else {
            intent.getStringExtra(EXTRA_ADB_TEXT)?.trim()?.take(500).orEmpty()
        }
        if (text.isEmpty()) return
        dbg("You: $text")

        // Warn-first ladder: every reply is a potential grace pass or lockout appeal.
        runCatching { com.shiina.mobile.action.ShiinaAccessibilityService.instance?.onUserChat(text) }

        // Release locked trigger overlay if active
        runCatching {
            val unlock = Intent(this, CharacterOverlayService::class.java).apply {
                action = CharacterOverlayService.ACTION_UNLOCK
            }
            startService(unlock)
        }

        // Echo to ChatBus if not echoed already
        if (!intent.getBooleanExtra(EXTRA_ECHO, false)) {
            ChatBus.echoUser(text)
        }

        container.userActivityTracker.recordActivity()

        scope.launch {
            runCatching { container.memoryOutcomes.onTalkReply() }
            runCatching { container.moodEngine.applyUserText(text) }

            val lastUserTurn = runCatching {
                container.chatHistory.getPreviousTurnTimestamp(text)
            }.getOrNull() ?: 0L
            val hoursInactive = if (lastUserTurn > 0L) {
                ((System.currentTimeMillis() - lastUserTurn).coerceAtLeast(0L) / 3_600_000.0)
            } else {
                container.userActivityTracker.getHoursSinceLastActive()
            }

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

            // Zero-bypass: execute directly through the AgentEngine
            val reply = runCatching {
                agentEngine.runAgentLoop(
                    userText = text,
                    initialTone = lastTone,
                    initialMode = lastMode,
                    isSystemTrigger = false,
                    hoursInactive = hoursInactive,
                    onProgress = { progress ->
                        pushTextToOverlay(progress)
                    },
                )
            }.getOrElse { e ->
                AppDebugServer.log("ERROR", "DebugTalk chat failed: ${e.message}")
                "Gemini unreachable (${e.message ?: "error"}). Check API keys in Settings."
            }

            lastReply = reply
            runCatching { container.chatHistory.addShiina(reply) }
            dbg("Shiina ($lastTone): $reply")
            pushTextToOverlay(reply)
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val isLate = currentHour >= 23 || currentHour < 5
            ChatBus.speak(reply, lastTone, isLate)

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
                    ),
                )
            }
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val i = Intent(this, DebugTalkService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun mutableActionIntent(action: String, code: Int): PendingIntent {
        val i = Intent(this, DebugTalkService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

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

        val isBusy = ChatBus.busy.value
        val progressText = ChatBus.progress.value

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(if (isBusy) "Shiina is working... · tone=$lastTone" else "Shiina debug · tone=$lastTone · logs=${if (logsEnabled) "ON" else "OFF"}")
            .setContentText(if (isBusy && progressText.isNotBlank()) progressText else headsUp ?: "Her words float above the bubble — tap Talk to chat.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tone: $lastTone · Mode: $lastMode\nLatest: $lastReply"))

        if (isBusy) {
            builder.addAction(android.R.drawable.ic_delete, "Stop", actionIntent(ACTION_STOP, 4))
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "Render", actionIntent(ACTION_RENDER, 1))
            builder.addAction(talkAction)
            builder.addAction(android.R.drawable.ic_menu_info_details, "Logs", actionIntent(ACTION_TOGGLE_LOGS, 2))
        }

        return builder.build()
    }

    private fun triggerBootGreeting() {
        scope.launch {
            if (isGreetingInProgress) return@launch
            val now = System.currentTimeMillis()
            if (now - lastGreetingTimestamp < 10 * 60 * 1000L) return@launch
            val lastTurn = runCatching { container.chatHistory.allTurns().lastOrNull() }.getOrNull()
            if (lastTurn != null && now - lastTurn.timestampMillis < 10 * 60 * 1000L) return@launch

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
                        lastTone = ShiinaPrompts.moodForTone(recentTone)
                    }
                }

                dbg("Triggering boot greeting with mood: $lastTone (inactive: ${"%.1f".format(hoursInactive)}h)")
                val bootPrompt = ShiinaPrompts.bootGreetingPrompt(lastTone, hoursInactive)

                val reply = runCatching {
                    agentEngine.runAgentLoop(
                        userText = bootPrompt,
                        initialTone = lastTone,
                        initialMode = lastMode,
                        isSystemTrigger = true,
                        hoursInactive = hoursInactive,
                    )
                }.getOrElse { "" }

                if (reply.isNotBlank() && !reply.startsWith("Gemini unreachable") && !reply.startsWith("Action stopped")) {
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
        runCatching { container.voiceSpeaker.stop() }
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
        const val ACTION_STOP = "com.shiina.mobile.debug.STOP"
        const val ACTION_HIDE = "com.shiina.mobile.debug.HIDE"
        const val ACTION_RESET_SESSION = "com.shiina.mobile.debug.RESET_SESSION"
        const val ACTION_BOOT_GREETING = "com.shiina.mobile.debug.BOOT_GREETING"
        const val KEY_TALK = "key_talk"
        const val EXTRA_ADB_TEXT = "adb_text"
        const val EXTRA_ECHO = "echo"

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
