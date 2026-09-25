package com.shiina.mobile.action

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.shiina.mobile.CompanionApp
import com.shiina.mobile.character.CharacterMode
import com.shiina.mobile.character.CharacterOverlayService
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.decision.Decision
import com.shiina.mobile.decision.DecisionSummary
import com.shiina.mobile.decision.MemoryContext
import com.shiina.mobile.data.db.MemoryFact
import com.shiina.mobile.di.AppContainer
import java.util.Calendar
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * P1 Heartbeat Loop (action/ProactiveLoop.kt):
 * Inexact jittered 45-90 min AlarmManager tick plus BOOT_COMPLETED restore.
 * Each tick evaluates hard gates before running the decide+render pipeline:
 *  1. User time windows plus learned nudge hour (MemoryContext), late-night
 *     guardian (23-5, screen on) and binge-scroll guardian (>=120min
 *     entertainment + screen on + 8h nag cooldown).
 *  2. Screen must be on (DeviceSenses snapshot screen field == "on").
 *  3. Battery temp under ~40C via BatteryManager EXTRA_TEMPERATURE.
 *  4. Skip on low battery (DeviceSenses low_battery or <=15%).
 *  5. Respect presenceOnly kill switch (SettingsRepository.presenceOnly).
 *  6. Dismissal cooldown via MemoryOutcomes (cooldownActive()).
 *  7. Max 6 overlay shows per hour (MemoryEpisodeDao episodesSince).
 *
 * Each passing tick executes the same pipeline as AlarmReceiver.runEveningDecision
 * with source="loop", showing overlay ONLY IF decision.interrupt is true.
 * Next tick is always rescheduled (45-90 min jitter) regardless of gate outcomes.
 */
class ProactiveLoop : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED -> {
                        AppDebugServer.log("LOOP", "BOOT_COMPLETED received; restoring proactive heartbeat loop")
                        restorePending(context)
                    }
                    ACTION_PROACTIVE_TICK, ACTION_PROACTIVE_TICK_ALT, ACTION_TRIGGER -> {
                        runTick(context)
                    }
                    else -> {
                        runTick(context)
                    }
                }
            } catch (e: Exception) {
                AppDebugServer.log("ERROR", "ProactiveLoop onReceive failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PROACTIVE_TICK = "com.shiina.mobile.action.PROACTIVE_TICK"
        const val ACTION_PROACTIVE_TICK_ALT = "com.shiina.mobile.ACTION_PROACTIVE_TICK"
        const val ACTION_TRIGGER = "com.shiina.mobile.ACTION_ALARM_TRIGGER"
        const val REQUEST_CODE_TICK = 4590

        const val MIN_TICK_MINUTES = 45L
        const val MAX_TICK_MINUTES = 90L
        const val MAX_BATTERY_TEMP_CELSIUS = 40.0
        const val MAX_OVERLAY_SHOWS_PER_HOUR = 6
        const val ONE_HOUR_MS = 3_600_000L
        // Binge-nag: daily entertainment minutes before she calls out scrolling.
        const val BINGE_THRESHOLD_MINUTES = 120
        // Min gap between binge nags (~3/day max). Stored at 0.2 confidence so
        // recall (cutoff 0.3) never surfaces it in her context.
        const val BINGE_NAG_COOLDOWN_MS = 8 * ONE_HOUR_MS
        const val KEY_LAST_BINGE_NAG = "sys_last_binge_nag"

        /** Public entry to restore or start the loop (e.g. on boot or app startup). */
        fun restorePending(context: Context) {
            scheduleNextTick(context)
        }

    /** Schedule a tick with a specific interval in minutes (1 to 60). */
        fun scheduleNextTickInMinutes(context: Context, minutes: Int) {
            runCatching {
                val am = context.getSystemService(AlarmManager::class.java) ?: return
                val clampedMinutes = minutes.coerceIn(1, 60)
                val delayMillis = clampedMinutes * 60_000L
                val triggerAt = System.currentTimeMillis() + delayMillis

                val intent = Intent(context, ProactiveLoop::class.java).apply {
                    action = ACTION_PROACTIVE_TICK
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_TICK,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                try {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    AppDebugServer.log("LOOP", "Next proactive tick scheduled by Shiina in ${clampedMinutes}m (at $triggerAt)")
                } catch (_: SecurityException) {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    AppDebugServer.log("LOOP", "Next proactive tick scheduled (inexact fallback) in ${clampedMinutes}m")
                }
            }.onFailure { e ->
                AppDebugServer.log("ERROR", "Failed to schedule next proactive tick: ${e.message}")
            }
        }

        /** Schedule an inexact jittered 45-90 min tick via AlarmManager. */
        fun scheduleNextTick(context: Context) {
            scheduleNextTickInMinutes(context, Random.nextInt(MIN_TICK_MINUTES.toInt(), MAX_TICK_MINUTES.toInt() + 1))
        }

        /** Cancel pending tick alarm. */
        fun cancel(context: Context) {
            runCatching {
                val am = context.getSystemService(AlarmManager::class.java) ?: return
                val intent = Intent(context, ProactiveLoop::class.java).apply {
                    action = ACTION_PROACTIVE_TICK
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_TICK,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                if (pi != null) {
                    am.cancel(pi)
                    pi.cancel()
                    AppDebugServer.log("LOOP", "Proactive heartbeat tick alarm cancelled")
                }
            }
        }

        /** Main execution of one heartbeat tick: gates -> pipeline -> reschedule. */
        suspend fun runTick(context: Context) {
            try {
                AppDebugServer.log("LOOP", "Proactive heartbeat tick fired, evaluating gates...")
                if (!checkAllGates(context)) {
                    AppDebugServer.log("LOOP", "Proactive tick skipped by hard gates")
                    scheduleNextTick(context)
                    return
                }
                AppDebugServer.log("LOOP", "All hard gates passed; running decision pipeline...")
                runDecisionPipeline(context)
            } catch (e: Exception) {
                AppDebugServer.log("ERROR", "Proactive tick execution error: ${e.message}")
                scheduleNextTick(context)
            }
        }

        /** Evaluates all 7 hard gates before decision pipeline runs. */
        suspend fun checkAllGates(context: Context): Boolean {
            val c = container(context)
            val snapshotStr = runCatching { c.deviceSenses.snapshot() }.getOrDefault("{}")
            val sensesJson = runCatching { JSONObject(snapshotStr) }.getOrNull()

            if (!checkScreenGate(sensesJson)) return false
            if (!checkBatteryTempGate(context)) return false
            if (!checkBatteryLevelGate(sensesJson)) return false
            if (!checkPresenceOnlyGate(c)) return false
            if (!checkDismissalCooldownGate(c)) return false
            if (!checkOverlayHourlyCapGate(c)) return false
            if (!checkTimeWindowGate(c, sensesJson)) return false

            return true
        }

        // Gate 1: User time windows plus learned nudge hour (MemoryContext) & Late-Night Guardian
        private suspend fun checkTimeWindowGate(c: AppContainer, sensesJson: JSONObject?): Boolean {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val screen = sensesJson?.optString("screen", "off") ?: "off"
            val isScreenOn = screen.equals("on", ignoreCase = true)

            // 1a. Real-world Late-Night Circadian Guardian (11 PM - 4:59 AM)
            // If the user is actively using the device late at night, Shiina must intervene!
            if ((hour >= 23 || hour < 5) && isScreenOn) {
                AppDebugServer.log("LOOP", "Time gate passed: late-night active phone usage detected (hour=$hour, screen=on). Shiina will intervene to guard sleep routine.")
                return true
            }

            // 1a2. Binge-scroll Guardian: entertainment minutes over threshold + screen on.
            // Forces a pass like the late-night guardian so she can call out rotting.
            if (isScreenOn) {
                val bingeMinutes = bingeMinutesIfDue(c)
                if (bingeMinutes != null) {
                    AppDebugServer.log("LOOP", "Time gate passed: binge-scroll detected (${bingeMinutes}min entertainment today >= ${BINGE_THRESHOLD_MINUTES}min). Shiina will intervene.")
                    return true
                }
            }

            // 1b. Learned nudge hour check
            val learnedHour = runCatching {
                c.database.baselineDao().get(MemoryContext.KEY_NUDGE_HOUR)?.average?.toInt()
            }.getOrNull()
            if (learnedHour != null && hour == learnedHour) {
                AppDebugServer.log("LOOP", "Time gate passed: matches learned nudge hour ($learnedHour:00)")
                return true
            }

            // 1c. Learned bedtime fact (suppress if in bedtime and screen is off, but intervened above if screen was on)
            val bedtime = runCatching {
                c.database.memoryFactDao().get("bedtime")?.value
            }.getOrNull()
            if (!bedtime.isNullOrBlank()) {
                val bedHour = bedtime.substringBefore(":").trim().toIntOrNull()
                if (bedHour != null) {
                    val inBed = if (bedHour >= 12) hour >= bedHour || hour < 7 else hour in bedHour..7
                    if (inBed) {
                        AppDebugServer.log("LOOP", "Gate rejected: bedtime active ($bedtime, hour=$hour)")
                        return false
                    }
                }
            }

            // 1d. Learned active_window fact (e.g. "09:00-22:00")
            val activeWindow = runCatching {
                c.database.memoryFactDao().get("active_window")?.value
            }.getOrNull()
            if (!activeWindow.isNullOrBlank()) {
                val parts = activeWindow.split("-")
                if (parts.size == 2) {
                    val start = parts[0].substringBefore(":").trim().toIntOrNull()
                    val end = parts[1].substringBefore(":").trim().toIntOrNull()
                    if (start != null && end != null) {
                        val inWindow = if (start <= end) hour in start..end else hour >= start || hour <= end
                        if (!inWindow) {
                            AppDebugServer.log("LOOP", "Gate rejected: outside active_window ($activeWindow, hour=$hour)")
                            return false
                        }
                        return true
                    }
                }
            }

            // 1e. Default user active window: 8 AM to 10 PM
            if (hour !in 8..22) {
                AppDebugServer.log("LOOP", "Gate rejected: outside default active window 8-22 (currentHour=$hour)")
                return false
            }
            return true
        }

        // Gate 2: Screen must be on (DeviceSenses snapshot screen field)
        private fun checkScreenGate(sensesJson: JSONObject?): Boolean {
            val screen = sensesJson?.optString("screen", "off") ?: "off"
            if (!screen.equals("on", ignoreCase = true)) {
                AppDebugServer.log("LOOP", "Gate rejected: screen is $screen (must be 'on')")
                return false
            }
            return true
        }

        // Gate 3: Battery temp under ~40C via BatteryManager EXTRA_TEMPERATURE
        private fun checkBatteryTempGate(context: Context): Boolean {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val statusIntent = context.registerReceiver(null, filter)
            val tempTenths = statusIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (tempTenths > 0) {
                val tempCelsius = tempTenths / 10.0
                if (tempCelsius >= MAX_BATTERY_TEMP_CELSIUS) {
                    AppDebugServer.log("LOOP", "Gate rejected: battery temp ${tempCelsius}°C >= ${MAX_BATTERY_TEMP_CELSIUS}°C")
                    return false
                }
            }
            return true
        }

        // Gate 4: Skip on low battery
        private fun checkBatteryLevelGate(sensesJson: JSONObject?): Boolean {
            val lowBattery = sensesJson?.optBoolean("low_battery", false) ?: false
            val batteryPct = sensesJson?.optInt("battery", -1) ?: -1
            if (lowBattery || (batteryPct in 0..15)) {
                AppDebugServer.log("LOOP", "Gate rejected: low battery ($batteryPct%, lowFlag=$lowBattery)")
                return false
            }
            return true
        }

        // Gate 5: Respect presenceOnly kill switch
        private suspend fun checkPresenceOnlyGate(c: AppContainer): Boolean {
            val presenceOnly = runCatching {
                c.settingsRepository.presenceOnly.first()
            }.getOrDefault(false)
            if (presenceOnly) {
                AppDebugServer.log("LOOP", "Gate rejected: presenceOnly kill switch active")
                return false
            }
            return true
        }

        // Gate 6: Dismissal cooldown via MemoryOutcomes
        private suspend fun checkDismissalCooldownGate(c: AppContainer): Boolean {
            val cooldown = runCatching {
                c.memoryOutcomes.cooldownActive()
            }.getOrDefault(false)
            if (cooldown) {
                AppDebugServer.log("LOOP", "Gate rejected: dismissal cooldown active")
                return false
            }
            return true
        }

        // Gate 7: Max 6 overlay shows per hour
        private suspend fun checkOverlayHourlyCapGate(c: AppContainer): Boolean {
            val oneHourAgo = System.currentTimeMillis() - ONE_HOUR_MS
            val showsInLastHour = runCatching {
                c.database.memoryEpisodeDao().episodesSince(oneHourAgo).count { it.shown }
            }.getOrDefault(0)
            if (showsInLastHour >= MAX_OVERLAY_SHOWS_PER_HOUR) {
                AppDebugServer.log(
                    "LOOP",
                    "Gate rejected: overlay hourly cap reached ($showsInLastHour >= $MAX_OVERLAY_SHOWS_PER_HOUR)",
                )
                return false
            }
            return true
        }

        /**
         * Binge-scroll check: today's entertainment minutes >= threshold AND no
         * binge nag within the cooldown window. Returns minutes when due, else null.
         */
        private suspend fun bingeMinutesIfDue(c: AppContainer): Int? {
            val cooldownOk = runCatching {
                val last = c.database.memoryFactDao().get(KEY_LAST_BINGE_NAG)?.updatedMillis ?: 0L
                System.currentTimeMillis() - last >= BINGE_NAG_COOLDOWN_MS
            }.getOrDefault(true)
            if (!cooldownOk) return null
            val minutes = runCatching { c.usageReader.getTodayEntertainmentMinutes() }.getOrDefault(0)
            return if (minutes >= BINGE_THRESHOLD_MINUTES) minutes else null
        }

        /** Stamps the binge-nag cooldown marker (0.2 confidence: invisible to recall). */
        private suspend fun stampBingeNag(c: AppContainer) {
            runCatching {
                c.database.memoryFactDao().upsert(
                    MemoryFact(KEY_LAST_BINGE_NAG, "nagged", 0.2, "system", System.currentTimeMillis()),
                )
            }
        }

        /**
         * Runs the same pipeline as AlarmReceiver.runEveningDecision with source=loop,
         * overlay only if decide returns interrupt true.
         */
        private suspend fun runDecisionPipeline(context: Context) {
            val c = container(context)
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val snapshotStr = runCatching { c.deviceSenses.snapshot() }.getOrDefault("{}")
            val sensesJson = runCatching { JSONObject(snapshotStr) }.getOrNull()
            val fgApp = sensesJson?.optString("foreground_app", "") ?: ""
            val battery = sensesJson?.optInt("battery", -1) ?: -1
            val isCharging = sensesJson?.optBoolean("charging", false) ?: false

            AppDebugServer.log("LOOP", "Proactive heartbeat tick triggered — delegating situation evaluation to Shiina...")

            // Zero hardcoded strings or canned decisions: let Shiina herself evaluate the situation
            val agent = com.shiina.mobile.decision.AgentEngine(context, c)
            val bingeMinutes = runCatching { c.usageReader.getTodayEntertainmentMinutes() }.getOrDefault(0)
            val bingeBaseline = runCatching { c.baselineUpdater.getEntertainmentBaseline() }.getOrDefault(0)
            val bingeFlag = if (bingeMinutes >= BINGE_THRESHOLD_MINUTES) " (OVER your ${BINGE_THRESHOLD_MINUTES}min binge line — fair game to call out)" else ""
            val situationPrompt = "[PROACTIVE_SITUATION_CHECK] Live physical senses: current_hour=$hour, battery=$battery%, charging=$isCharging, foreground_app='$fgApp', entertainment_today=${bingeMinutes}min (usual ~${bingeBaseline}min)$bingeFlag. Observe the physical situation yourself. If you determine an authentic check-in, caring intervention (e.g. late night active phone use, binge-scrolling past the line), or timely reminder is right, speak naturally in your own authentic voice. If everything is fine or she should not be disturbed right now, conclude with status DONE and no message."

            val agentRun = runCatching {
                agent.runAgentLoopInternal(
                    userText = situationPrompt,
                    initialTone = c.moodEngine.currentMood(),
                    initialMode = CharacterMode.WANDER.name,
                    isSystemTrigger = true,
                )
            }.getOrNull()

            val reply = agentRun?.message?.trim() ?: ""
            val nextInterval = agentRun?.nextCheckInMinutes ?: -1

            // If Shiina autonomously decided to intervene
            if (reply.isNotBlank() && reply != "(empty reply)" && !reply.startsWith("{") && !reply.contains("\"thought\":")) {
                val currentTone = c.moodEngine.currentMood()
                AppDebugServer.log("LOOP", "Shiina dynamically decided to check in ($currentTone): $reply")

                val show = Intent(context, CharacterOverlayService::class.java).apply {
                    action = CharacterOverlayService.ACTION_SHOW
                    putExtra(CharacterOverlayService.EXTRA_TONE, currentTone)
                    putExtra(CharacterOverlayService.EXTRA_MESSAGE, reply)
                    putExtra(CharacterOverlayService.EXTRA_MODE, CharacterMode.WANDER.name)
                    putExtra(CharacterOverlayService.EXTRA_INTERRUPT, true)
                }
                runCatching { context.startForegroundService(show) }
                    .onFailure { e ->
                        AppDebugServer.log("ERROR", "Proactive overlay start failed: ${e.message}")
                    }

                // Voice TTS & Chat history
                val isLateNight = hour >= 23 || hour < 5
                com.shiina.mobile.debug.ChatBus.speak(reply, currentTone, isLateNight)
                runCatching { c.chatHistory.addShiina(reply) }
                if (bingeMinutes >= BINGE_THRESHOLD_MINUTES) stampBingeNag(c)
            } else {
                AppDebugServer.log("LOOP", "Shiina evaluated the situation and autonomously decided no intervention was needed.")
            }

            // Reschedule next heartbeat based on Shiina's dynamic choice (1 to 60 mins), or fallback to standard jitter
            if (nextInterval in 1..60) {
                AppDebugServer.log("LOOP", "Rescheduling next heartbeat in ${nextInterval}m as chosen by Shiina.")
                scheduleNextTickInMinutes(context, nextInterval)
            } else {
                scheduleNextTick(context)
            }
        }

        private fun container(context: Context): AppContainer =
            (context.applicationContext as CompanionApp).container
    }
}

/**
 * BroadcastReceiver alias for ProactiveLoop so both class names can be resolved.
 */
class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ProactiveLoop().onReceive(context, intent)
    }
}
