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
import com.shiina.mobile.observation.BriefingContextBuilder
import com.shiina.mobile.observation.BriefingInputs
import com.shiina.mobile.observation.CalendarReader
import com.shiina.mobile.observation.NotificationDigest
import com.shiina.mobile.observation.TimingGate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
 *
 * Phase 1 (Smart Contextual Briefings): each tick first assembles a structured
 * briefing snapshot (period, battery, upcoming calendar events, unread
 * notifications, foreground app, music) and runs it through a smart timing gate
 * so she checks in only at relevant moments — morning pickup, post-work
 * transition, low-battery warning, late-night / binge guardians, learned hours.
 *
 * Hard gates (any failure skips the tick and reschedules):
 *  1. Screen must be on (DeviceSenses snapshot screen field == "on").
 *  2. Battery temp under ~40C via BatteryManager EXTRA_TEMPERATURE.
 *  3. Critical battery (<=10%) is never woken for an LLM turn.
 *  4. Respect presenceOnly kill switch (SettingsRepository.presenceOnly).
 *  5. Dismissal cooldown via MemoryOutcomes (cooldownActive()).
 *  6. Max 6 overlay shows per hour (MemoryEpisodeDao episodesSince).
 *  7. Smart timing gate (BriefingContextBuilder.resolveTimingGate).
 *
 * Each passing tick executes the same pipeline as AlarmReceiver.runEveningDecision
 * with source="loop", showing overlay ONLY IF decision.interrupt is true.
 * Next tick is always rescheduled regardless of gate outcomes.
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
                        // AUDIT finding #2: never let an unrecognized action spawn a paid
                        // LLM turn. The receiver is also unexported in the manifest; this is
                        // defence in depth so a future re-export cannot reopen the hole.
                        AppDebugServer.log("LOOP", "ProactiveLoop ignored unknown action: ${intent.action}")
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
        // Battery at/below this is never woken for an LLM turn (saving the last electrons).
        const val CRITICAL_BATTERY_SKIP_PERCENT = 10
        // Once-a-day smart-briefing markers (0.2 confidence so recall never surfaces them).
        const val KEY_LAST_MORNING_BRIEF = "sys_last_morning_brief"
        const val KEY_LAST_POSTWORK_BRIEF = "sys_last_postwork_brief"

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

        /** Main execution of one heartbeat tick: briefing -> gates -> pipeline -> reschedule. */
        suspend fun runTick(context: Context) {
            try {
                AppDebugServer.log("LOOP", "Proactive heartbeat tick fired, assembling briefing...")
                val inputs = collectBriefingInputs(context)
                val gate = evaluateGates(context, inputs)
                if (!gate.allowed) {
                    AppDebugServer.log("LOOP", "Proactive tick skipped by gates (${gate.label}: ${gate.reason})")
                    scheduleNextTick(context)
                    return
                }
                AppDebugServer.log("LOOP", "All gates passed (${gate.label}); running decision pipeline...")
                runDecisionPipeline(context, inputs, gate)
            } catch (e: Exception) {
                AppDebugServer.log("ERROR", "Proactive tick execution error: ${e.message}")
                scheduleNextTick(context)
            }
        }

        /** Collects the structured briefing snapshot from local device state. */
        suspend fun collectBriefingInputs(context: Context): BriefingInputs {
            val c = container(context)
            val sensesJson = runCatching { JSONObject(c.deviceSenses.snapshot()) }.getOrNull()
            val now = Calendar.getInstance()
            val batteryPct = liveBatteryPercent(context)
                .takeIf { it >= 0 } ?: (sensesJson?.optInt("battery", -1) ?: -1)
            val events = runCatching { CalendarReader(context).upcomingEvents() }.getOrDefault(emptyList())
            val nowMillis = System.currentTimeMillis()
            val minutesUntil = events.firstOrNull()
                ?.let { ((it.beginMillis - nowMillis) / 60_000L).toInt() }
                ?.coerceAtLeast(0) ?: -1
            return BriefingInputs(
                hour = now.get(Calendar.HOUR_OF_DAY),
                minute = now.get(Calendar.MINUTE),
                dayOfWeek = now.get(Calendar.DAY_OF_WEEK),
                batteryPercent = batteryPct,
                charging = sensesJson?.optBoolean("charging", false) ?: false,
                screenOn = (sensesJson?.optString("screen", "off") ?: "off").equals("on", ignoreCase = true),
                foregroundApp = sensesJson?.optString("foreground_app", "") ?: "",
                musicPlaying = sensesJson?.optBoolean("music_playing", false) ?: false,
                calendarEvents = events.map { formatEvent(it) },
                nextEventMinutesUntil = minutesUntil,
                unreadCount = runCatching { NotificationDigest.unreadCount() }.getOrDefault(0),
                unreadSenders = runCatching { NotificationDigest.topSenders() }.getOrDefault(emptyList()),
            )
        }

        /** Evaluates the hard gates, then the smart timing gate. */
        suspend fun evaluateGates(context: Context, inputs: BriefingInputs): TimingGate {
            val c = container(context)
            if (!inputs.screenOn) return TimingGate(false, "screen_off", "screen is off")
            if (!checkBatteryTempGate(context)) return TimingGate(false, "battery_hot", "battery temperature too high")
            if (!checkCriticalBatteryGate(inputs)) return TimingGate(false, "critical_battery", "battery too low to wake")
            if (!checkPresenceOnlyGate(c)) return TimingGate(false, "presence_only", "presenceOnly kill switch active")
            if (!checkDismissalCooldownGate(c)) return TimingGate(false, "dismissal_cooldown", "dismissal cooldown active")
            if (!checkOverlayHourlyCapGate(c)) return TimingGate(false, "overlay_cap", "overlay hourly cap reached")

            val learnedHour = runCatching {
                c.database.baselineDao().get(MemoryContext.KEY_NUDGE_HOUR)?.average?.toInt()
            }.getOrNull()
            val bedtime = runCatching { c.database.memoryFactDao().get("bedtime")?.value }.getOrNull()
            val activeWindow = runCatching { c.database.memoryFactDao().get("active_window")?.value }.getOrNull()
            // Respect the binge-nag cooldown: only surface binge minutes when a nag is actually due.
            val bingeMinutes = bingeMinutesIfDue(c) ?: 0

            return BriefingContextBuilder.resolveTimingGate(
                inputs = inputs,
                learnedHour = learnedHour,
                activeWindow = activeWindow,
                bedtime = bedtime,
                bingeMinutes = bingeMinutes,
                bingeThresholdMinutes = BINGE_THRESHOLD_MINUTES,
                lastMorningBriefMillis = markerMillis(c, KEY_LAST_MORNING_BRIEF),
                lastPostWorkBriefMillis = markerMillis(c, KEY_LAST_POSTWORK_BRIEF),
                nowMillis = System.currentTimeMillis(),
            )
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

        // Battery: only a truly critical charge is skipped; 11-20% becomes a briefing trigger instead.
        private fun checkCriticalBatteryGate(inputs: BriefingInputs): Boolean {
            if (inputs.batteryPercent in 0..CRITICAL_BATTERY_SKIP_PERCENT) {
                AppDebugServer.log("LOOP", "Gate rejected: critical battery (${inputs.batteryPercent}%)")
                return false
            }
            return true
        }

        private fun liveBatteryPercent(context: Context): Int = runCatching {
            context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        }.getOrDefault(-1)

        private suspend fun markerMillis(c: AppContainer, key: String): Long =
            runCatching { c.database.memoryFactDao().get(key)?.updatedMillis ?: 0L }.getOrDefault(0L)

        /** Stamps a once-a-day briefing marker (0.2 confidence: invisible to recall). */
        private suspend fun stampMarker(c: AppContainer, key: String) {
            runCatching {
                c.database.memoryFactDao().upsert(
                    MemoryFact(key, "briefed", 0.2, "system", System.currentTimeMillis()),
                )
            }
        }

        private fun formatEvent(e: com.shiina.mobile.observation.CalendarEvent): String {
            val whenText = if (e.allDay) "all-day" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(e.beginMillis))
            val loc = if (e.location.isNotBlank()) " @ ${e.location}" else ""
            return "$whenText ${e.title}$loc"
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
        private suspend fun runDecisionPipeline(context: Context, inputs: BriefingInputs, gate: TimingGate) {
            val c = container(context)
            val hour = inputs.hour

            val briefingJson = BriefingContextBuilder.build(inputs, gate)
            AppDebugServer.log("LOOP", "BRIEFING $briefingJson")
            val briefingBlock = BriefingContextBuilder.promptBlock(inputs, gate)

            AppDebugServer.log("LOOP", "Proactive heartbeat tick triggered — delegating situation evaluation to Shiina...")

            // Zero hardcoded strings or canned decisions: let Shiina herself evaluate the situation
            val agent = com.shiina.mobile.decision.AgentEngine(context, c)
            val bingeMinutes = runCatching { c.usageReader.getTodayEntertainmentMinutes() }.getOrDefault(0)
            val bingeBaseline = runCatching { c.baselineUpdater.getEntertainmentBaseline() }.getOrDefault(0)
            val bingeFlag = if (bingeMinutes >= BINGE_THRESHOLD_MINUTES) " (OVER your ${BINGE_THRESHOLD_MINUTES}min binge line — fair game to call out)" else ""
            val situationPrompt = "$briefingBlock\n[PROACTIVE_SITUATION_CHECK] Live physical senses: current_hour=$hour, battery=${inputs.batteryPercent}%, charging=${inputs.charging}, foreground_app='${inputs.foregroundApp}', entertainment_today=${bingeMinutes}min (usual ~${bingeBaseline}min)$bingeFlag. Observe the physical situation yourself. If you determine an authentic check-in, caring intervention (e.g. late night active phone use, binge-scrolling past the line), or timely reminder is right, speak naturally in your own authentic voice. If everything is fine or she should not be disturbed right now, conclude with status DONE and no message."

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
                when (gate.label) {
                    "morning_pickup" -> stampMarker(c, KEY_LAST_MORNING_BRIEF)
                    "post_work_transition" -> stampMarker(c, KEY_LAST_POSTWORK_BRIEF)
                }
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
