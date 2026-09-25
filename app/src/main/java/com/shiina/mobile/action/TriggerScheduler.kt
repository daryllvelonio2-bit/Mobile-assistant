package com.shiina.mobile.action

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.shiina.mobile.CompanionApp
import com.shiina.mobile.character.CharacterMode
import com.shiina.mobile.character.CharacterOverlayService
import com.shiina.mobile.data.db.TriggerDao
import com.shiina.mobile.data.db.TriggerEntry
import com.shiina.mobile.debug.AppDebugServer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Behavior triggers she can set on herself: "scold me when it's 12",
 * "nag me about homework at 9pm", "every day at 11pm force me to sleep".
 * When the time comes, a LOCKED overlay fires — it ignores HIDE and cannot
 * be dragged away; only replying to her (Talk) unlocks it. Strict keywords
 * (scold/sleep/bed/strict/nag) use firm_warning tone.
 */
class TriggerScheduler(
    private val context: Context,
    private val dao: TriggerDao,
) {

    /**
     * Contract: NEVER returns "" on success — success returns
     * "Locked in — at EEE h:mm a ...". A "" return means NO_TIME (no time
     * phrase found) or PAST_TIME, and callers map "" to a "no time found"
     * reply. Fire times more than MAX_AHEAD_MS (30 days) out are rejected
     * with an explanatory message (mirrors ReminderScheduler).
     *
     * Time grammar (Regex style, checked top-down): "in N min/hr",
     * "at H(:MM)? (am|pm)?", "when it's H", bare "12pm"/"12 pm",
     * "midnight"/"noon", "tomorrow at H", "every day at H".
     */
    suspend fun parseAndSchedule(rawText: String): String {
        val now = System.currentTimeMillis()
        val atM = REGEX_AT.find(rawText)
        val inM = REGEX_IN_N.find(rawText)
        val whenItsM = REGEX_WHEN_ITS.find(rawText)
        val bareM = REGEX_BARE_AMPM.find(rawText)
        val midnightNoonM = REGEX_MIDNIGHT_NOON.find(rawText)
        val wantsTomorrow = REGEX_TOMORROW.containsMatchIn(rawText)
        val daily = REGEX_DAILY.containsMatchIn(rawText)

        val label = rawText
            .replace(Regex("(?i)^(please\\s+)?(scold|nag|yell at|remind|tell|force) me( to| about)?\\s*"), "")
            .replace(Regex("(?i)\\bwhen\\s+it'?s\\s+\\d{1,2}(?::\\d{2})?\\s*(am|pm)?"), "")
            .replace(Regex("(?i)\\bat\\s+\\d{1,2}(?::\\d{2})?\\s*(am|pm)?"), "")
            .replace(Regex("(?i)\\b\\d{1,2}(?::\\d{2})?\\s*(am|pm)\\b"), "")
            .replace(Regex("(?i)\\b(midnight|noon)\\b"), "")
            .replace(Regex("(?i)\\bin\\s+\\d+\\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours)\\b"), "")
            .replace(Regex("(?i)\\b(tomorrow|tmr|tom|every\\s+day|daily|every\\s+night)\\b"), "")
            .replace(Regex("(?i)\\b(when it'?s|when its|at)\\s+.*$"), "")
            .trim().take(140)

        val cal = Calendar.getInstance()
        when {
            inM != null -> {
                val n = inM.groupValues[1].toInt()
                val minutes = if (inM.groupValues[2].lowercase(Locale.US).startsWith("h")) n * 60 else n
                cal.timeInMillis = now + minutes * 60_000L
            }
            atM != null -> applyWallClock(cal, atM, now, wantsTomorrow)
            whenItsM != null -> applyWallClock(cal, whenItsM, now, wantsTomorrow)
            bareM != null -> applyWallClock(cal, bareM, now, wantsTomorrow)
            midnightNoonM != null -> {
                val isMidnight = midnightNoonM.groupValues[1].lowercase(Locale.US) == "midnight"
                applyWallClockHour(cal, if (isMidnight) 0 else 12, 0, now, wantsTomorrow)
            }
            else -> return "" // NO_TIME
        }
        if (cal.timeInMillis <= now) return "" // PAST_TIME
        if (cal.timeInMillis > now + MAX_AHEAD_MS) {
            return "That's more than 30 days out — I can only hold triggers a month ahead."
        }

        val text = label.ifEmpty { rawText.take(80) }
        val strict = STRICT_WORDS.any { it in rawText.lowercase(Locale.US) }
        val id = dao.insert(
            TriggerEntry(
                text = text,
                fireMillis = cal.timeInMillis,
                createdMillis = System.currentTimeMillis(),
                repeatType = if (daily) "daily" else "none",
                strict = strict,
            ),
        )
        scheduleAlarm(cal.timeInMillis, id.toInt())
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        AppDebugServer.log("TRIGGER", "Trigger #$id set: \"$text\" at ${fmt.format(cal.time)} strict=$strict daily=$daily")
        return "Locked in — at ${fmt.format(cal.time)} I'll ${if (strict) "hold you to it (no dismissing me)" else "trigger"}: \"$text\"${if (daily) ", every day" else ""}."
    }

    suspend fun listTriggers(): String {
        val pending = dao.pendingAfter(System.currentTimeMillis())
        if (pending.isEmpty()) return "no triggers set"
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        return pending.joinToString("; ") { t ->
            "#${t.id} \"${t.text.take(30)}\" ${fmt.format(t.fireMillis)}" +
                (if (t.repeatType == "daily") " daily" else "") +
                (if (t.strict) " [strict]" else "")
        }
    }

    suspend fun cancelTrigger(query: String): String {
        val pending = dao.pendingAfter(System.currentTimeMillis())
        if (pending.isEmpty()) return "no triggers to cancel"
        val q = query.trim()
        val target = q.toLongOrNull()?.let { id -> pending.firstOrNull { it.id == id } }
            ?: pending.firstOrNull { it.text.equals(q, ignoreCase = true) }
            ?: pending.firstOrNull { q.isNotEmpty() && it.text.contains(q, ignoreCase = true) }
        return if (target == null) {
            "no trigger matching \"${q.take(30)}\""
        } else {
            cancelAlarm(target.id.toInt())
            dao.delete(target.id)
            AppDebugServer.log("TRIGGER", "Trigger #${target.id} cancelled: ${target.text}")
            "cancelled trigger: \"${target.text.take(30)}\""
        }
    }

    /** Boot path: re-arm every persisted trigger still in the future. */
    suspend fun restorePending() {
        val now = System.currentTimeMillis()
        dao.pendingAfter(now).forEach { t -> scheduleAlarm(t.fireMillis, t.id.toInt()) }
        AppDebugServer.log("TRIGGER", "Triggers restored after boot: ${dao.pendingAfter(now).size}")
    }

    /** Public re-arm hook for TriggerReceiver (daily roll-over). */
    fun rearm(atMillis: Long, requestCode: Int) = scheduleAlarm(atMillis, requestCode)

    private fun scheduleAlarm(atMillis: Long, requestCode: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val i = Intent(context, TriggerReceiver::class.java).apply {
            action = ACTION_TRIGGER
            putExtra(EXTRA_ID, requestCode.toLong())
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun cancelAlarm(requestCode: Int) {
        runCatching {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val i = Intent(context, TriggerReceiver::class.java).apply { action = ACTION_TRIGGER }
            val pi = PendingIntent.getBroadcast(
                context, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.cancel(pi)
        }
    }

    /** Pure wall-clock resolver shared by at/when-it's/bare patterns (unit-safe: now passed in). */
    private fun applyWallClock(cal: Calendar, m: MatchResult, now: Long, wantsTomorrow: Boolean) {
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val ap = m.groupValues[3].lowercase(Locale.US)
        if (ap == "pm" && hour < 12) hour += 12
        if (ap == "am" && hour == 12) hour = 0
        if (ap.isEmpty() && hour <= cal.get(Calendar.HOUR_OF_DAY)) hour += 12
        if (hour > 23) hour -= 24
        applyWallClockHour(cal, hour, minute, now, wantsTomorrow)
    }

    /** Pure wall-clock setter with tomorrow/roll-forward handling (unit-safe: now passed in). */
    private fun applyWallClockHour(cal: Calendar, hour: Int, minute: Int, now: Long, wantsTomorrow: Boolean) {
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (wantsTomorrow) cal.add(Calendar.DAY_OF_YEAR, 1)
        else if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
    }

    companion object {
        const val ACTION_TRIGGER = "com.shiina.mobile.ACTION_TRIGGER"
        const val EXTRA_ID = "trigger_id"
        private const val MAX_AHEAD_MS = 30L * 86_400_000L
        private val REGEX_AT = Regex("at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
        private val REGEX_WHEN_ITS = Regex("when\\s+it'?s\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
        private val REGEX_BARE_AMPM = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b", RegexOption.IGNORE_CASE)
        private val REGEX_MIDNIGHT_NOON = Regex("\\b(midnight|noon)\\b", RegexOption.IGNORE_CASE)
        private val REGEX_IN_N = Regex("in\\s+(\\d+)\\s*(m|min|mins|minute|minutes|h|hr|hrs|hour|hours)\\b", RegexOption.IGNORE_CASE)
        private val REGEX_TOMORROW = Regex("\\b(tomorrow|tmr|tom)\\b", RegexOption.IGNORE_CASE)
        private val REGEX_DAILY = Regex("\\b(every\\s+day|daily|every\\s+night)\\b", RegexOption.IGNORE_CASE)
        private val STRICT_WORDS = listOf(
            "scold", "nag", "yell", "strict", "sleep", "bed", "force",
            "punish", "hold me", "don't let me", "dont let me",
        )
    }
}

/**
 * Fires the LOCKED overlay at trigger time. Strict triggers use firm tone;
 * the message names the app the user is still on (she monitors usage).
 * Daily triggers roll to the next day; one-shots are deleted.
 */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(TriggerScheduler.EXTRA_ID, -1L)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val container = (context.applicationContext as CompanionApp).container
                val dao = container.database.triggerDao()
                val entry = if (id >= 0) dao.get(id) else null
                val text = entry?.text.orEmpty()
                if (text.isNotEmpty()) {
                    val senses = runCatching { container.deviceSenses.snapshot() }.getOrDefault("{}")
                    val app = runCatching { JSONObject(senses).optString("foreground_app", "") }.getOrDefault("")
                    val appLine = if (app.isNotBlank() && !app.startsWith("unknown")) " Still on $app." else ""
                    val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                    val strict = entry?.strict ?: false
                    val tone = if (strict) "firm_warning" else "candid_direct"
                    val msg = if (strict) {
                        "It's $timeStr — you told me to hold you to this: $text.$appLine Replying to me unlocks this."
                    } else {
                        "Trigger: $text$appLine"
                    }
                    val show = Intent(context, CharacterOverlayService::class.java).apply {
                        action = CharacterOverlayService.ACTION_SHOW
                        putExtra(CharacterOverlayService.EXTRA_TONE, tone)
                        putExtra(CharacterOverlayService.EXTRA_MESSAGE, msg)
                        putExtra(CharacterOverlayService.EXTRA_MODE, CharacterMode.WANDER.name)
                        putExtra(CharacterOverlayService.EXTRA_LOCKED, strict)
                    }
                    // Log BEFORE startForegroundService so a throw still leaves a trace,
                    // including overlay-permission + locked (rate-limit bypass) state.
                    val canOverlay = Settings.canDrawOverlays(context)
                    AppDebugServer.log(
                        "TRIGGER",
                        "Trigger #$id firing: \"$text\" (strict=$strict locked=$strict overlayPermission=$canOverlay)",
                    )
                    runCatching {
                        context.startForegroundService(show)
                    }.onFailure { e ->
                        AppDebugServer.log("ERROR", "Trigger #$id overlay start failed: ${e.message}")
                        postFallbackNotification(context, id, text)
                    }
                    if (!canOverlay) postFallbackNotification(context, id, text)
                }
                if (entry != null) {
                    if (entry.repeatType == "daily") {
                        val next = nextDailyOccurrence(entry.fireMillis)
                        dao.update(entry.copy(fireMillis = next))
                        container.triggerScheduler.rearm(next, entry.id.toInt())
                        AppDebugServer.log("TRIGGER", "Daily trigger #${entry.id} re-armed for next wall-clock occurrence")
                    } else {
                        dao.delete(entry.id)
                    }
                }
                container.memoryOutcomes.onActionExecuted()
            }.onFailure { e ->
                AppDebugServer.log("ERROR", "Trigger #$id failed: ${e.message}\n${e.stackTraceToString()}")
            }
            pending.finish()
        }
    }

    /**
     * Next daily occurrence at the same wall-clock time (DST-safe: sets
     * H/M fields instead of adding a fixed 86_400_000ms).
     */
    private fun nextDailyOccurrence(fireMillis: Long): Long {
        val src = Calendar.getInstance().apply { timeInMillis = fireMillis }
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, src.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, src.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var guard = 0
        while (next.timeInMillis <= System.currentTimeMillis() && guard++ < 3) {
            next.add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis
    }

    /** Fallback so something always appears when the overlay can't show. */
    private fun postFallbackNotification(context: Context, id: Long, text: String) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Triggers", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val n = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Shiina trigger")
                .setContentText(text.take(200))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            nm.notify(FALLBACK_NOTIFY_BASE + (id % 1000).toInt(), n)
            AppDebugServer.log("TRIGGER", "Trigger #$id fallback notification posted")
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "Trigger #$id fallback notification failed: ${e.message}")
        }
    }

    companion object {
        private const val CHANNEL_ID = "trigger_fallback"
        private const val FALLBACK_NOTIFY_BASE = 9000
    }
}