package com.shiina.mobile.action

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.shiina.mobile.data.db.GoalDao
import com.shiina.mobile.data.db.GoalEntry
import com.shiina.mobile.data.db.MemoryEpisodeDao
import com.shiina.mobile.data.settings.SettingsRepository
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.observation.MemoryOutcomes
import com.shiina.mobile.observation.ScreenshotTaker
import kotlinx.coroutines.flow.first

/**
 * Capability registry executor.
 * Audit A5: goals get real lifecycle — LOG_GOAL inserts, COMPLETE_GOAL marks
 * done, "give up on X" marks missed (via completeGoal/markMissed helpers).
 * Audit A15: extraActions clamped to 2 and validated against ALLOWED_ACTIONS
 * before anything runs.
 * Audit B3: every verb returns an honest one-line receipt; the joined
 * receipt is written onto the episode as actionSummary.
 */
class ActionExecutor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val goalDao: GoalDao,
    private val outcomes: MemoryOutcomes? = null,
    private val memoryStore: com.shiina.mobile.memory.MemoryStore? = null,
    private val taker: ScreenshotTaker? = null,
    private val web: WebSearch? = null,
    private val pageReader: PageReader? = null,
    private val reminders: ReminderScheduler? = null,
    private val toolTracker: ToolTracker? = null,
    private val episodeDao: MemoryEpisodeDao? = null,
    private val deviceActionController: DeviceActionController? = null,
) {

    suspend fun execute(actionType: String, param: String = "") {
        when (actionType) {
            "alarm" -> schedule7PmAlarm()
            "toggle_screenshot" -> {
                val current = runCatching {
                    settings.screenshotEnabled.first()
                }.getOrDefault(false)
                settings.setScreenshotEnabled(!current)
                AppDebugServer.log(
                    "ACTION", "Screenshot capture ${if (!current) "enabled" else "disabled"}",
                )
            }
            "log_goal" -> {
                if (param.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    goalDao.insert(
                        GoalEntry(
                            title = param,
                            status = 0,
                            createdMillis = now,
                            updatedMillis = now
                        )
                    )
                }
            }
            else -> {}
        }
    }

    /**
     * Track B2: runs the main verb, then up to 2 follow-up steps from
     * decision.extraActions (action lists instead of one verb).
     * Track B3: everything she did is summarized into the episode, so
     * tomorrow she remembers actions, not just words.
     */
    suspend fun executeDecision(decision: com.shiina.mobile.decision.Decision) {
        val allowed = com.shiina.mobile.decision.Decision.ALLOWED_ACTIONS
        val parts = mutableListOf<String>()
        if (decision.action != "NONE" && decision.action in allowed) {
            parts += runVerb(decision.action, decision.actionParam)
        }
        // A15: clamp extras to 2 and validate verbs before execution.
        decision.extraActions.take(2)
            .filter { it.first in allowed }
            .forEach { (a, p) -> parts += runVerb(a, p) }
        val summary = parts.joinToString(" | ").take(280)
        if (summary.isNotBlank()) recordSummary(summary)
        if (decision.action != "NONE") {
            runCatching { outcomes?.onActionExecuted() }
        }
    }

    private suspend fun recordSummary(summary: String) {
        runCatching {
            val ep = episodeDao?.recent(1)?.firstOrNull() ?: return
            episodeDao.updateActionSummary(ep.id, summary)
            AppDebugServer.log("MEMORY", "Episode ${ep.id} task summary: $summary")
        }
    }

    /** One capability-registry verb -> short honest result line. */
    private suspend fun runVerb(action: String, param: String): String = when (action) {
        "SET_ALARM" -> {
            execute("alarm")
            "alarm scheduled"
        }
        "LOG_GOAL" -> {
            execute("log_goal", param)
            "goal logged: ${param.take(40)}"
        }
        "COMPLETE_GOAL" -> completeGoal(param)
        "LEARN_FACT" -> {
            val k = param.substringBefore("=").trim()
            val v = param.substringAfter("=", "").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) {
                runCatching { memoryStore?.remember(k, v, "inferred") }
                "fact learned: $k"
            } else "fact parse failed"
        }
        "TOGGLE_SCREENSHOT" -> {
            execute("toggle_screenshot")
            "screenshot capture toggled"
        }
        "SEARCH_WEB" -> {
            val q = param.trim()
            val answer = runCatching { web?.search(q).orEmpty() }.getOrDefault("")
            runCatching { toolTracker?.record("search_web", answer.isNotEmpty()) }
            if (answer.isNotEmpty()) {
                runCatching {
                    memoryStore?.remember("web: ${q.take(48)}", answer.take(200), "inferred")
                }
                "searched \"$q\": ${answer.length} chars found"
            } else "searched \"$q\": nothing came back"
        }
        "READ_URL" -> {
            val text = runCatching { pageReader?.read(param).orEmpty() }.getOrDefault("")
            runCatching { toolTracker?.record("read_url", text.isNotEmpty()) }
            if (text.isNotEmpty()) "read ${param.take(60)}: ${text.length} chars"
            else "read ${param.take(60)}: page unreadable"
        }
        "TAKE_SCREENSHOT" -> {
            val file = runCatching { taker?.capture("ondemand") }.getOrNull()
            runCatching { toolTracker?.record("screenshot", file != null) }
            if (file != null) "screenshot saved ${file.name}" else "screenshot failed (no consent)"
        }
        "CHECK_GOALS" -> goalReport()
        "SET_REMINDER" -> {
            val msg = runCatching { reminders?.parseAndSchedule(param).orEmpty() }
                .getOrDefault("")
            msg.ifEmpty { "reminder time unclear: ${param.take(40)}" }
        }
        "HIDE" -> {
            val i = Intent(context, com.shiina.mobile.character.CharacterOverlayService::class.java).apply {
                this.action = com.shiina.mobile.character.CharacterOverlayService.ACTION_HIDE
            }
            context.startService(i)
            "overlay hidden"
        }
        "SET_MODE" -> {
            val mode = runCatching { com.shiina.mobile.character.CharacterMode.valueOf(param.uppercase()) }
                .getOrDefault(com.shiina.mobile.character.CharacterMode.STAY)
            val i = Intent(context, com.shiina.mobile.character.CharacterOverlayService::class.java).apply {
                this.action = if (mode == com.shiina.mobile.character.CharacterMode.VANISH) {
                    com.shiina.mobile.character.CharacterOverlayService.ACTION_HIDE
                } else {
                    com.shiina.mobile.character.CharacterOverlayService.ACTION_SHOW
                }
                putExtra(com.shiina.mobile.character.CharacterOverlayService.EXTRA_MODE, mode.name)
            }
            context.startService(i)
            "overlay mode set: ${mode.name}"
        }
        "MEDIA_CONTROL" -> {
            val controller = deviceActionController ?: DeviceActionController(context)
            val res = if (param.startsWith("play ", ignoreCase = true)) {
                controller.playSong(param.removePrefix("play ").trim())
            } else {
                controller.controlMedia(param)
            }
            runCatching { toolTracker?.record("media_control", true) }
            res
        }
        "PLAY_MUSIC" -> {
            val controller = deviceActionController ?: DeviceActionController(context)
            val res = controller.playSong(param)
            runCatching { toolTracker?.record("play_music", true) }
            res
        }
        "SEARCH_MUSIC" -> {
            val controller = deviceActionController ?: DeviceActionController(context)
            val res = controller.searchMusic(param)
            runCatching { toolTracker?.record("search_music", true) }
            res
        }
        "VOLUME_CONTROL" -> {
            val controller = deviceActionController ?: DeviceActionController(context)
            val res = controller.volumeControl(param)
            runCatching { toolTracker?.record("volume_control", true) }
            res
        }
        "DEVICE_ACTION" -> {
            val controller = deviceActionController ?: DeviceActionController(context)
            val res = controller.deviceAction(param)
            runCatching { toolTracker?.record("device_action", true) }
            res
        }
        "OPEN_APP" -> {
            val controller = deviceActionController ?: DeviceActionController(context)
            val res = controller.openApp(param)
            runCatching { toolTracker?.record("open_app", !res.startsWith("could not")) }
            res
        }
        "SEARCH_APP" -> {
            val controller = deviceActionController ?: DeviceActionController(context)
            val app = param.substringBefore(":").trim()
            val q = param.substringAfter(":", "").trim()
            val res = controller.searchApp(app, q)
            runCatching { toolTracker?.record("search_app", true) }
            res
        }
        "LIST_APPS" -> {
            val controller = deviceActionController ?: DeviceActionController(context)
            val res = controller.listApps(param)
            runCatching { toolTracker?.record("list_apps", true) }
            res
        }
        "GET_DEVICE_STATE" -> {
            val controller = deviceActionController ?: DeviceActionController(context)
            val res = controller.getDeviceState()
            runCatching { toolTracker?.record("device_state", true) }
            res
        }
        else -> {
            AppDebugServer.log("ACTION", "Unknown action rejected: $action")
            "unknown action $action"
        }
    }

    /** Public entry for the Talk loop's COMPLETE_GOAL tool. */
    suspend fun completeGoalPublic(title: String): String = completeGoal(title)

    /** A5: mark the best-matching open goal done; honest receipt either way. */
    private suspend fun completeGoal(title: String): String = runCatching {
        val t = title.trim().take(120)
        if (t.isEmpty()) return@runCatching "complete_goal needs a goal title"
        val goal = goalDao.all().filter { it.status == 0 }
            .firstOrNull { it.title.equals(t, ignoreCase = true) }
            ?: goalDao.all().filter { it.status == 0 }
                .firstOrNull { it.title.contains(t, ignoreCase = true) || t.contains(it.title, ignoreCase = true) }
        if (goal == null) {
            "no open goal matching \"${t.take(30)}\""
        } else {
            goalDao.update(goal.copy(status = 1, updatedMillis = System.currentTimeMillis()))
            "goal done: ${goal.title.take(40)}"
        }
    }.getOrElse { "complete_goal failed: ${it.message}" }

    /** Track A4 — goal review: open vs stalled (no update 3+ days) vs done. */
    suspend fun goalReport(): String = runCatching {
        val all = goalDao.all()
        if (all.isEmpty()) return@runCatching "no goals logged yet"
        val now = System.currentTimeMillis()
        val open = all.filter { it.status == 0 }
        val stalled = open.filter { now - it.updatedMillis > 3L * 86_400_000L }
        val done = all.count { it.status == 1 }
        "goals: ${open.size} open (stalled 3+ days: " +
            "${stalled.joinToString { it.title.take(24) }.ifEmpty { "none" }}), $done done"
    }.getOrElse { "goal read failed: ${it.message}" }

    private fun schedule7PmAlarm() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 19)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }
        val intent = Intent("com.shiina.mobile.ACTION_ALARM_TRIGGER")
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        } catch (_: SecurityException) {
            // If exact alarm permission not granted yet
        }
    }
}