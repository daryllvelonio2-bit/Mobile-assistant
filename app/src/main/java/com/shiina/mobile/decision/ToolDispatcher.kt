package com.shiina.mobile.decision

import android.content.Context
import android.content.Intent
import com.shiina.mobile.character.CharacterMode
import com.shiina.mobile.character.CharacterOverlayService
import com.shiina.mobile.di.AppContainer
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

data class ToolResult(
    val receipt: String,
    val shouldAttachScreenshot: Boolean,
    val waitMs: Long = 1000L,
)

object ToolDispatcher {

    val KNOWN_TOOLS = setOf(
        // Meta
        "GET_TOOLSET",
        // Apps
        "OPEN_APP", "CLOSE_APP", "GET_FOREGROUND_APP", "LIST_APPS", "SEARCH_APP",
        "OPEN_URL", "SYSTEM_INTENT", "INSPECT_SCREEN", "READ_SCREEN_TEXT", "TAKE_SCREENSHOT",
        "TAP_SCREEN", "SWIPE_SCREEN", "INPUT_TEXT", "CLEAR_TEXT", "PRESS_KEY", "WAIT",
        // Device
        "TOGGLE_FLASHLIGHT", "OPEN_SETTINGS", "MANAGE_CLIPBOARD", "GET_DEVICE_STATE",
        "VOLUME_CONTROL", "SET_RINGER_MODE", "VIBRATE_DEVICE", "SEND_NOTIFICATION", "SET_MODE",
        // Media
        "SEARCH_MUSIC", "PLAY_MUSIC", "MEDIA_CONTROL", "GET_CURRENT_PLAYING",
        // Web
        "SEARCH_WEB", "READ_URL",
        // Planner & Memory
        "REMEMBER", "FORGET", "LIST_FACTS", "LOG_GOAL", "CHECK_GOALS", "COMPLETE_GOAL",
        "SET_REMINDER", "LIST_REMINDERS", "CANCEL_REMINDER",
        "SET_TRIGGER", "LIST_TRIGGERS", "CANCEL_TRIGGER",
        "SET_ALARM", "SET_TIMER",
        // Procedural App Navigation Memory & Macro Self-Optimization
        "GET_PROCEDURE", "LEARN_PROCEDURE", "REMOVE_PROCEDURE_STEP",
        "UPDATE_PROCEDURE_STEP", "OPTIMIZE_PROCEDURE", "LIST_PROCEDURES", "FORGET_PROCEDURE",
        "EXECUTE_PROCEDURE",
        // Fallback
        "NONE",
    )

    fun buildStepSchema(): JSONObject {
        val toolEnum = JSONArray()
        for (t in KNOWN_TOOLS) {
            toolEnum.put(t)
        }
        val moodEnum = JSONArray().put("calm").put("candid").put("warm").put("firm").put("pouty").put("excited").put("melancholy")
        val statusEnum = JSONArray().put("CONTINUE").put("DONE")

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
                    .put("hour", JSONObject().put("type", "INTEGER"))
                    .put("minute", JSONObject().put("type", "INTEGER"))
                    .put("seconds", JSONObject().put("type", "INTEGER"))
                    .put("title", JSONObject().put("type", "STRING"))
                    .put("url", JSONObject().put("type", "STRING"))
                    .put("mode", JSONObject().put("type", "STRING"))
                    .put("key", JSONObject().put("type", "STRING"))
                    .put("value", JSONObject().put("type", "STRING"))
                    .put("element_id", JSONObject().put("type", "INTEGER"))
                    .put("x", JSONObject().put("type", "NUMBER"))
                    .put("y", JSONObject().put("type", "NUMBER"))
                    .put("startX", JSONObject().put("type", "NUMBER"))
                    .put("startY", JSONObject().put("type", "NUMBER"))
                    .put("endX", JSONObject().put("type", "NUMBER"))
                    .put("endY", JSONObject().put("type", "NUMBER"))
                    .put("text", JSONObject().put("type", "STRING"))
                    .put("direction", JSONObject().put("type", "STRING"))
                    .put("is_long_press", JSONObject().put("type", "BOOLEAN")),
            )

        return JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("thought", JSONObject().put("type", "STRING"))
                    .put("mood", JSONObject().put("type", "STRING").put("enum", moodEnum))
                    .put("status", JSONObject().put("type", "STRING").put("enum", statusEnum))
                    .put("tool", JSONObject().put("type", "STRING").put("enum", toolEnum))
                    .put("next_check_in_minutes", JSONObject().put("type", "INTEGER").put("description", "Shiina's chosen delay in minutes (1 to 60) until the next proactive heartbeat tick."))
                    .put("tool_args", toolArgsSchema)
                    .put(
                        "message",
                        JSONObject()
                            .put("type", "STRING")
                            .put("description", "Natural conversational response spoken to the user. Leave empty \"\" or silent if Shiina decides not to say anything right now."),
                    ),
            )
            .put(
                "required",
                JSONArray()
                    .put("thought")
                    .put("status")
                    .put("tool"),
            )
    }

    suspend fun dispatch(step: AgentStep, container: AppContainer, context: Context): ToolResult {
        return runCatching {
            when (step.tool) {
                "GET_TOOLSET" -> {
                    val name = step.toolset.ifBlank { step.query.ifBlank { step.text } }
                    ToolResult(ToolCatalog.getToolset(name), shouldAttachScreenshot = false, waitMs = 300L)
                }
                "OPEN_APP" -> {
                    val app = step.app.ifBlank { step.query.ifBlank { step.text } }
                    val receipt = container.deviceActionController.openApp(app)
                    ToolResult("$receipt. App launched; allowing splash screen and UI loading to settle.", shouldAttachScreenshot = true, waitMs = 2500L)
                }
                "CLOSE_APP" -> {
                    val receipt = container.deviceActionController.closeApp()
                    ToolResult(receipt, shouldAttachScreenshot = true, waitMs = 1200L)
                }
                "GET_FOREGROUND_APP" -> {
                    val receipt = container.deviceActionController.getForegroundApp()
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "LIST_APPS" -> {
                    val f = step.filter.ifBlank { step.query }
                    val receipt = container.deviceActionController.listApps(f)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "SEARCH_APP" -> {
                    val app = step.app.ifBlank {
                        container.deviceActionController.getForegroundApp()
                            .substringAfter("Foreground app: ")
                            .substringBefore(" (")
                            .trim()
                    }
                    val q = step.query.ifBlank { step.text }
                    if (app.isBlank()) {
                        ToolResult("[ERROR] SEARCH_APP requires 'app' parameter specifying target application.", shouldAttachScreenshot = false, waitMs = 0L)
                    } else {
                        val receipt = container.deviceActionController.searchApp(app, q)
                        ToolResult("$receipt. Search dispatched; allowing results to render.", shouldAttachScreenshot = true, waitMs = 2500L)
                    }
                }
                "OPEN_URL" -> {
                    val u = step.url.ifBlank { step.query.ifBlank { step.text } }
                    val receipt = container.deviceActionController.openUrl(u)
                    ToolResult(receipt, shouldAttachScreenshot = true, waitMs = 2000L)
                }
                "SYSTEM_INTENT" -> {
                    val act = step.action.ifBlank { step.query }
                    val data = step.text.ifBlank { step.query }
                    val receipt = container.deviceActionController.systemIntent(act, data)
                    ToolResult(receipt, shouldAttachScreenshot = true, waitMs = 1500L)
                }
                "INSPECT_SCREEN" -> {
                    val receipt = container.deviceActionController.getScreenElementsSummary()
                    ToolResult(receipt, shouldAttachScreenshot = true, waitMs = 500L)
                }
                "READ_SCREEN_TEXT" -> {
                    val receipt = container.deviceActionController.readScreenText()
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "TAKE_SCREENSHOT" -> {
                    val f = runCatching { container.screenshotTaker.capture("talk") }.getOrNull()
                    val receipt = if (f != null) {
                        "Screenshot captured successfully. The screen frame is attached as an image for visual verification."
                    } else {
                        "Screenshot capture failed (permission not granted or display busy)."
                    }
                    ToolResult(receipt, shouldAttachScreenshot = f != null, waitMs = 500L)
                }
                "TAP_SCREEN" -> {
                    val receipt = container.deviceActionController.tapScreen(
                        x = step.x,
                        y = step.y,
                        text = step.text,
                        elementId = step.elementId,
                        isLongPress = step.isLongPress,
                    )
                    ToolResult(receipt, shouldAttachScreenshot = true, waitMs = 1500L)
                }
                "SWIPE_SCREEN" -> {
                    val receipt = container.deviceActionController.swipeScreen(
                        startX = step.startX,
                        startY = step.startY,
                        endX = step.endX,
                        endY = step.endY,
                        direction = step.direction,
                    )
                    ToolResult(receipt, shouldAttachScreenshot = true, waitMs = 1200L)
                }
                "INPUT_TEXT" -> {
                    val receipt = container.deviceActionController.inputText(step.text.ifBlank { step.query })
                    ToolResult(receipt, shouldAttachScreenshot = true, waitMs = 1000L)
                }
                "CLEAR_TEXT" -> {
                    val receipt = container.deviceActionController.clearText()
                    ToolResult(receipt, shouldAttachScreenshot = true, waitMs = 500L)
                }
                "PRESS_KEY" -> {
                    val receipt = container.deviceActionController.pressKey(step.action.ifBlank { step.query })
                    ToolResult(receipt, shouldAttachScreenshot = true, waitMs = 1000L)
                }
                "WAIT" -> {
                    val parsedSeconds = step.level.takeIf { it > 0 }
                        ?: step.query.toIntOrNull()
                        ?: step.text.toIntOrNull()
                        ?: step.action.toIntOrNull()
                        ?: 2
                    val seconds = parsedSeconds.coerceIn(1, 10)
                    delay(seconds * 1000L)
                    ToolResult("Waited ${seconds}s for UI rendering, network requests, and animations to settle. Fresh visual screenshot attached.", shouldAttachScreenshot = true, waitMs = 400L)
                }
                "TOGGLE_FLASHLIGHT" -> {
                    val act = step.action.ifBlank { step.query }
                    val receipt = container.deviceActionController.toggleFlashlight(act)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "OPEN_SETTINGS" -> {
                    val panel = step.action.ifBlank { step.query }
                    val receipt = container.deviceActionController.openSettings(panel)
                    ToolResult(receipt, shouldAttachScreenshot = true, waitMs = 1500L)
                }
                "MANAGE_CLIPBOARD" -> {
                    val act = step.action.ifBlank { "read" }
                    val txt = step.text.ifBlank { step.query }
                    val receipt = container.deviceActionController.manageClipboard(act, txt)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "GET_DEVICE_STATE" -> {
                    val receipt = container.deviceActionController.getDeviceState()
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "VOLUME_CONTROL" -> {
                    val act = step.action.ifBlank { step.query.ifBlank { step.text } }
                    val receipt = container.deviceActionController.volumeControl(act, step.level)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "SET_RINGER_MODE" -> {
                    val act = step.action.ifBlank { step.query }
                    val receipt = container.deviceActionController.setRingerMode(act)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "VIBRATE_DEVICE" -> {
                    val ms = if (step.level > 0) step.level.toLong() else 200L
                    val receipt = container.deviceActionController.vibrateDevice(ms)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "SEND_NOTIFICATION" -> {
                    val t = step.title.ifBlank { "Shiina Alert" }
                    val msg = step.text.ifBlank { step.query }
                    val receipt = container.deviceActionController.sendNotification(t, msg)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "SEARCH_MUSIC" -> {
                    val q = step.query.ifBlank { step.text }
                    val receipt = container.deviceActionController.searchMusic(q)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "PLAY_MUSIC" -> {
                    val q = step.query.ifBlank { step.text.ifBlank { step.action } }
                    val receipt = container.deviceActionController.playSong(q, step.player)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 500L)
                }
                "MEDIA_CONTROL" -> {
                    val act = step.action.ifBlank { step.text }
                    val q = step.query
                    val receipt = if (q.isNotBlank() && (act.isBlank() || act.equals("play", ignoreCase = true))) {
                        container.deviceActionController.playSong(q, step.player)
                    } else {
                        container.deviceActionController.controlMedia(act.ifEmpty { q })
                    }
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "GET_CURRENT_PLAYING" -> {
                    val receipt = container.deviceActionController.getCurrentPlaying()
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "SEARCH_WEB" -> {
                    val q = step.query.ifBlank { step.text }
                    val res = runCatching { container.webSearch.search(q.ifBlank { "latest news" }) }.getOrDefault("")
                    val receipt = if (res.isNotBlank()) res else "Web search returned no results."
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 500L)
                }
                "READ_URL" -> {
                    val u = step.url.ifBlank { step.query }
                    val res = runCatching { container.pageReader.read(u) }.getOrDefault("")
                    val receipt = if (res.isNotBlank()) res else "Could not read page content."
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 500L)
                }
                "REMEMBER" -> {
                    var k = step.key
                    var v = step.value
                    if (k.isBlank() || v.isBlank()) {
                        val combined = "${step.thought} ${step.query} ${step.text}"
                        val nameMatch = Regex("(?:name is|called|user is|named) ([a-zA-Z]+)", RegexOption.IGNORE_CASE).find(combined)
                        if (nameMatch != null) {
                            k = "user_name"
                            v = nameMatch.groupValues[1].replaceFirstChar { it.uppercase() }
                        }
                    }
                    val receipt = if (k.isNotBlank() && v.isNotBlank()) {
                        runCatching {
                            container.learnedMemoryManager.learn(k, v, "agent_tool")
                            container.memoryStore.remember(k, v, "stated")
                            "Remembered $k = $v"
                        }.getOrDefault("Failed to save memory")
                    } else "REMEMBER requires 'key' and 'value'."
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "FORGET" -> {
                    val k = step.key.ifBlank { step.query.ifBlank { step.text } }
                    val receipt = if (k.isNotBlank()) {
                        runCatching {
                            container.learnedMemoryManager.forget(k)
                            container.memoryStore.forgetKey(k)
                            "Forgot $k"
                        }.getOrDefault("Failed to forget $k")
                    } else "FORGET requires 'key'."
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "LIST_FACTS" -> {
                    val mem = runCatching { container.learnedMemoryManager.readMemory() }.getOrDefault("")
                    val receipt = if (mem.isNotBlank()) mem else "No facts remembered yet."
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "LOG_GOAL" -> {
                    val t = step.title.ifBlank { step.text.ifBlank { step.query } }
                    val receipt = if (t.isNotBlank()) {
                        runCatching {
                            container.actionExecutor.execute("log_goal", t)
                            "Goal logged: $t"
                        }.getOrDefault("Failed to log goal")
                    } else "LOG_GOAL requires a 'title'."
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "CHECK_GOALS" -> {
                    val receipt = runCatching { container.actionExecutor.goalReport() }.getOrDefault("No goals logged.")
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "COMPLETE_GOAL" -> {
                    val t = step.title.ifBlank { step.text.ifBlank { step.query } }
                    val receipt = runCatching { container.actionExecutor.completeGoalPublic(t) }.getOrDefault("Failed to complete goal")
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "SET_REMINDER" -> {
                    val msg = runCatching {
                        container.reminderScheduler.parseAndSchedule(step.text.ifBlank { step.query }).orEmpty()
                    }.getOrDefault("")
                    val receipt = msg.ifBlank { "Could not parse reminder time. Specify when to remind." }
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "LIST_REMINDERS" -> {
                    val receipt = runCatching { container.reminderScheduler.listReminders() }.getOrDefault("No reminders set.")
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "CANCEL_REMINDER" -> {
                    val receipt = runCatching { container.reminderScheduler.cancelReminder(step.text.ifBlank { step.query }) }.getOrDefault("Cancel reminder failed.")
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "SET_TRIGGER" -> {
                    val msg = runCatching {
                        container.triggerScheduler.parseAndSchedule(step.text.ifBlank { step.query }).orEmpty()
                    }.getOrDefault("")
                    val receipt = msg.ifBlank { "Could not parse trigger time. Specify when to trigger." }
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "LIST_TRIGGERS" -> {
                    val receipt = runCatching { container.triggerScheduler.listTriggers() }.getOrDefault("No triggers set.")
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "CANCEL_TRIGGER" -> {
                    val receipt = runCatching { container.triggerScheduler.cancelTrigger(step.text.ifBlank { step.query }) }.getOrDefault("Cancel trigger failed.")
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "SET_ALARM" -> {
                    val hour = step.hour.takeIf { it in 0..23 }
                        ?: step.level.takeIf { it in 0..23 }
                        ?: 7
                    val min = step.minute.takeIf { it in 0..59 }
                        ?: step.query.toIntOrNull()?.takeIf { it in 0..59 }
                        ?: 0
                    val receipt = container.deviceActionController.setAlarm(hour, min, step.title.ifBlank { step.text })
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 500L)
                }
                "SET_TIMER" -> {
                    val sec = if (step.seconds > 0) step.seconds else if (step.level > 0) step.level else 60
                    val receipt = container.deviceActionController.setTimer(sec, step.title.ifBlank { step.text })
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 500L)
                }
                "SET_MODE" -> {
                    val m = step.mode.ifBlank { step.query }.uppercase()
                    val target = when (m) {
                        "WANDER" -> CharacterMode.WANDER
                        "VANISH", "HIDE" -> CharacterMode.VANISH
                        else -> CharacterMode.STAY
                    }
                    val i = Intent(context, CharacterOverlayService::class.java).apply {
                        action = if (target == CharacterMode.VANISH) CharacterOverlayService.ACTION_HIDE else CharacterOverlayService.ACTION_SHOW
                        putExtra(CharacterOverlayService.EXTRA_MODE, target.name)
                    }
                    context.startForegroundService(i)
                    ToolResult("Character mode set to ${target.name}", shouldAttachScreenshot = false, waitMs = 300L)
                }
                "GET_PROCEDURE" -> {
                    val k = step.key.ifBlank { step.query.ifBlank { step.text.ifBlank { step.title } } }
                    val receipt = container.proceduralMemoryStore.getProcedureDetails(k)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "LEARN_PROCEDURE" -> {
                    val title = step.title.ifBlank { step.query }
                    val app = step.app.ifBlank { "app" }
                    val rawSteps = step.text.ifBlank { step.query }
                    val parsed = container.proceduralMemoryStore.parseStepsFromString(rawSteps)
                    val receipt = container.proceduralMemoryStore.addProcedure(
                        title = title,
                        appLabel = app,
                        appPackage = "",
                        intentGoal = step.query.ifBlank { title },
                        steps = parsed,
                        shortcutTip = step.action,
                    )
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "REMOVE_PROCEDURE_STEP" -> {
                    val k = step.key.ifBlank { step.title.ifBlank { step.query } }
                    val stepNum = if (step.level > 0) step.level else step.query.toIntOrNull() ?: 1
                    val receipt = container.proceduralMemoryStore.removeStep(k, stepNum)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "UPDATE_PROCEDURE_STEP" -> {
                    val k = step.key.ifBlank { step.title.ifBlank { step.query } }
                    val stepNum = if (step.level > 0) step.level else 1
                    val newAction = step.action
                    val newTarget = step.query
                    val newDetails = step.text
                    val receipt = container.proceduralMemoryStore.updateStep(k, stepNum, newAction, newTarget, newDetails)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "OPTIMIZE_PROCEDURE" -> {
                    val k = step.key.ifBlank { step.title }
                    val rawSteps = step.text.ifBlank { step.query }
                    val parsed = container.proceduralMemoryStore.parseStepsFromString(rawSteps)
                    val reason = step.title.ifBlank { "Streamlined steps discovered by Shiina" }
                    val receipt = container.proceduralMemoryStore.optimizeProcedure(k, parsed, reason)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "LIST_PROCEDURES" -> {
                    val summary = container.proceduralMemoryStore.getProceduresSummaryForPrompt(currentGoal = step.query.ifBlank { step.text })
                    val receipt = summary.ifBlank { "No procedures learned yet." }
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "FORGET_PROCEDURE" -> {
                    val k = step.key.ifBlank { step.title.ifBlank { step.query } }
                    val receipt = container.proceduralMemoryStore.deleteProcedure(k)
                    ToolResult(receipt, shouldAttachScreenshot = false, waitMs = 300L)
                }
                "EXECUTE_PROCEDURE" -> {
                    val k = step.key.ifBlank { step.title.ifBlank { step.query } }
                    val dynamicArg = step.query.ifBlank { step.text }
                    val entry = container.proceduralMemoryStore.getProcedure(k)
                        ?: container.proceduralMemoryStore.findBestMatch(k)
                    if (entry == null) {
                        ToolResult("Procedure '$k' not found in procedural memory.", shouldAttachScreenshot = false, waitMs = 300L)
                    } else {
                        executeProcedureSteps(entry, dynamicArg, container)
                    }
                }
                else -> ToolResult("Unknown tool '${step.tool}'", shouldAttachScreenshot = false, waitMs = 200L)
            }
        }.getOrElse { e ->
            ToolResult("Tool '${step.tool}' failed: ${e.message ?: "error"}", shouldAttachScreenshot = false, waitMs = 300L)
        }
    }

    private suspend fun executeProcedureSteps(
        entry: com.shiina.mobile.memory.ProcedureEntry,
        dynamicArg: String,
        container: AppContainer,
    ): ToolResult {
        com.shiina.mobile.debug.AppDebugServer.log("TOOL_DISPATCH", "Executing procedure '${entry.title}' (${entry.steps.size} steps)")
        var completed = 0

        for (s in entry.steps) {
            val target = s.target.trim()
            val action = s.action.trim().uppercase()
            com.shiina.mobile.debug.AppDebugServer.log("TOOL_DISPATCH", "Procedure step #${s.stepNumber}: $action target='$target'")

            val stepSuccess = runCatching {
                when (action) {
                    "OPEN_APP" -> {
                        container.deviceActionController.openApp(target)
                        delay(2000L)
                        true
                    }
                    "OPEN_SETTINGS" -> {
                        container.deviceActionController.openSettings(target)
                        delay(1500L)
                        true
                    }
                    "TAP_SCREEN", "TAP" -> {
                        val coordMatch = Regex("""\(?\s*(\d+)\s*,\s*(\d+)\s*\)?""").find(target)
                        if (coordMatch != null) {
                            val x = coordMatch.groupValues[1].toFloat()
                            val y = coordMatch.groupValues[2].toFloat()
                            val res = container.deviceActionController.tapScreen(x, y)
                            !res.startsWith("Could not")
                        } else {
                            val res = container.deviceActionController.tapScreen(-1f, -1f, text = target)
                            !res.startsWith("Could not")
                        }
                        delay(800L)
                        true
                    }
                    "INPUT_TEXT", "TYPE" -> {
                        val toType = if (dynamicArg.isNotBlank() && (target.equals("query", true) || target.equals("search", true) || target.equals("text", true) || target.equals("time", true))) {
                            dynamicArg
                        } else {
                            target
                        }
                        container.deviceActionController.inputText(toType)
                        delay(600L)
                        true
                    }
                    "PRESS_KEY" -> {
                        container.deviceActionController.pressKey(target)
                        delay(600L)
                        true
                    }
                    "WAIT" -> {
                        val sec = target.toLongOrNull() ?: 2L
                        delay(sec * 1000L)
                        true
                    }
                    "SET_ALARM" -> {
                        val hour = target.substringBefore(":").trim().toIntOrNull() ?: 7
                        val min = target.substringAfter(":").trim().toIntOrNull() ?: 0
                        container.deviceActionController.setAlarm(hour, min, entry.title)
                        delay(500L)
                        true
                    }
                    "SET_TIMER" -> {
                        val sec = target.toIntOrNull() ?: 60
                        container.deviceActionController.setTimer(sec, entry.title)
                        delay(500L)
                        true
                    }
                    else -> {
                        container.deviceActionController.tapScreen(-1f, -1f, text = target)
                        delay(800L)
                        true
                    }
                }
            }.getOrDefault(false)

            if (!stepSuccess) {
                com.shiina.mobile.debug.AppDebugServer.log("TOOL_DISPATCH", "Procedure stopped at step #${s.stepNumber} [${s.action} target='$target']")
                return ToolResult(
                    receipt = "Procedure '${entry.title}' halted at step #${s.stepNumber} [${s.action} target='$target']: Screen did not respond. Live screenshot captured for interactive recovery.",
                    shouldAttachScreenshot = true,
                    waitMs = 500L,
                )
            }
            completed++
        }

        entry.usageCount++
        entry.lastUsed = System.currentTimeMillis()
        com.shiina.mobile.debug.AppDebugServer.log("TOOL_DISPATCH", "Procedure '${entry.title}' completed successfully ($completed/${entry.steps.size} steps)")

        return ToolResult(
            receipt = "Procedure '${entry.title}' executed successfully ($completed/${entry.steps.size} steps completed). Active screen updated.",
            shouldAttachScreenshot = true,
            waitMs = 1000L,
        )
    }
}
