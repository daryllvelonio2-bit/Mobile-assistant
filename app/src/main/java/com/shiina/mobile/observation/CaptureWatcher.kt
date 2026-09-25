package com.shiina.mobile.observation

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import com.shiina.mobile.data.settings.SettingsRepository
import com.shiina.mobile.debug.AppDebugServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Watches the user's triggers and rolls a 30% capture chance on each:
 * foreground app change, orientation change, music start. Hard ceiling of
 * one capture per 15 minutes. Started/stopped by ObservationService.
 */
class CaptureWatcher(
    private val context: Context,
    private val taker: ScreenshotTaker,
    private val settings: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var configReceiver: BroadcastReceiver? = null

    @Volatile private var lastCaptureAt: Long = 0L
    @Volatile private var lastFgPkg: String = ""
    @Volatile private var lastOrientation: Int = context.resources.configuration.orientation
    @Volatile private var musicWasActive: Boolean = false

    fun start() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_CONFIGURATION_CHANGED) return
                val now = c.resources.configuration.orientation
                if (now != lastOrientation) {
                    lastOrientation = now
                    scope.launch { maybeCapture("rotation") }
                }
            }
        }
        configReceiver = receiver
        runCatching {
            val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "CaptureWatcher receiver failed: ${e.message}")
        }
        loop?.cancel()
        loop = scope.launch {
            // Seed so the current app doesn't count as an "open".
            lastFgPkg = currentForegroundPackage().orEmpty()
            musicWasActive = isMusicActive()
            while (isActive) {
                val screenOn = isScreenOn()
                if (!screenOn) {
                    delay(POLL_SCREEN_OFF_MS)
                    continue
                }
                runCatching {
                    val pkg = currentForegroundPackage()
                    if (pkg != null && pkg != lastFgPkg && pkg != context.packageName) {
                        lastFgPkg = pkg
                        maybeCapture("app:$pkg")
                    }
                    val music = isMusicActive()
                    if (music && !musicWasActive) maybeCapture("music")
                    musicWasActive = music
                }.onFailure { e ->
                    AppDebugServer.log("ERROR", "CaptureWatcher poll failed: ${e.message}")
                }
                delay(POLL_MS)
            }
        }
        AppDebugServer.log("CAPTURE", "Watcher started (30% roll, 15min cooldown)")
    }

    fun stop() {
        runCatching { loop?.cancel() }
        runCatching {
            configReceiver?.let { context.unregisterReceiver(it) }
            configReceiver = null
        }
        scope.cancel()
    }

    private suspend fun maybeCapture(reason: String) {
        if (!isScreenOn()) return
        if (!settings.screenshotEnabled.first()) return
        if (!taker.ready) {
            AppDebugServer.log("CAPTURE", "Trigger $reason: no projection consent yet")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastCaptureAt < COOLDOWN_MS) return
        val roll = Random.nextFloat()
        AppDebugServer.log("CAPTURE", "Trigger $reason: roll=$roll")
        if (roll >= CHANCE) return
        val file = taker.capture(reason.replace(Regex("[^A-Za-z0-9]+"), "_"))
        if (file != null) lastCaptureAt = now
    }

    private fun isScreenOn(): Boolean {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive ?: true
        }.getOrDefault(true)
    }

    private fun currentForegroundPackage(): String? {
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - POLL_MS - 5_000, end)
            var pkg: String? = null
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = ev.packageName
            }
            pkg
        } catch (_: Exception) {
            null
        }
    }

    private fun isMusicActive(): Boolean {
        return try {
            context.getSystemService(AudioManager::class.java)?.isMusicActive == true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val POLL_MS = 15_000L
        private const val POLL_SCREEN_OFF_MS = 60_000L
        private const val COOLDOWN_MS = 15 * 60 * 1_000L
        private const val CHANCE = 0.3f
    }
}
