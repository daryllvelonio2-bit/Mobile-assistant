package com.shiina.mobile.action

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shiina.mobile.CompanionApp
import com.shiina.mobile.character.CharacterMode
import com.shiina.mobile.character.CharacterOverlayService
import com.shiina.mobile.decision.DecisionSummary
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Daily 7PM trigger: runs the real decide+render pipeline (usage vs
 * baseline -> Gemini tone -> overlay), then rolls the alarm to tomorrow.
 * Also reschedules after reboot. Heavy work off-main, zero unhandled.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED -> {
                        val c = container(context)
                        c.actionExecutor.execute("alarm")
                        runCatching { c.reminderScheduler.restorePending() }
                            .onFailure { e ->
                                AppDebugServer.log("ERROR", "Reminder restore failed: ${e.message}")
                            }
                        AppDebugServer.log("ALARM", "Daily 7PM alarm rescheduled after boot")
                    }
                    ACTION_TRIGGER -> runEveningDecision(context)
                }
            } catch (e: Exception) {
                AppDebugServer.log("ERROR", "AlarmReceiver failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun runEveningDecision(context: Context) {
        val c = container(context)
        val minutes = c.usageReader.getTodayEntertainmentMinutes()
        val baseline = c.baselineUpdater.getEntertainmentBaseline()
        val decision = c.providerRegistry.decide(
            DecisionSummary(
                entertainmentMinutes = minutes,
                entertainmentBaseline = baseline,
                sleepBedMillis = 0L,
                sleepBaselineMillis = 0L,
                goalsOpen = 0,
                goalsDone = 0,
                goalsMissed = 0,
            ),
            source = "alarm",
        )
        AppDebugServer.log(
            "ALARM",
            "7PM decision: tone=${decision.tone} intent=${decision.intent} " +
                "action=${decision.action} interrupt=${decision.interrupt} :: ${decision.message}",
        )
        val show = Intent(context, CharacterOverlayService::class.java).apply {
            action = CharacterOverlayService.ACTION_SHOW
            putExtra(CharacterOverlayService.EXTRA_TONE, decision.tone)
            putExtra(CharacterOverlayService.EXTRA_MESSAGE, decision.message)
            putExtra(
                CharacterOverlayService.EXTRA_MODE,
                if (decision.interrupt) CharacterMode.WANDER.name else CharacterMode.STAY.name,
            )
        }
        context.startForegroundService(show)
        c.actionExecutor.executeDecision(decision)
        c.actionExecutor.execute("alarm")
    }

    private fun container(context: Context): AppContainer =
        (context.applicationContext as CompanionApp).container

    companion object {
        const val ACTION_TRIGGER = "com.shiina.mobile.ACTION_ALARM_TRIGGER"
    }
}
