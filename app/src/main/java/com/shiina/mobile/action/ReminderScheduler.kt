package com.shiina.mobile.action

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shiina.mobile.CompanionApp
import com.shiina.mobile.character.CharacterMode
import com.shiina.mobile.character.CharacterOverlayService
import com.shiina.mobile.data.db.ReminderDao
import com.shiina.mobile.data.db.ReminderEntry
import com.shiina.mobile.debug.AppDebugServer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Track A3 — reminders that fire: parses "remind me at 8pm", "in 20
 * minutes", "tomorrow at 9", "next monday at 10am"; schedules an alarm; at
 * fire time ReminderReceiver posts her overlay with the reminder text.
 * Audit A3: every reminder is persisted to Room first — reboot restores all
 * unfired rows (see restorePending), the receiver deletes on fire.
 * Audit F9: fire time must be future and within 30 days. Audit F10: falls
 * back to inexact alarm when SCHEDULE_EXACT_ALARM is denied.
 */
class ReminderScheduler(
    private val context: Context,
    private val dao: ReminderDao,
) {

    /** Returns a confirmation line, or "" when no time is parseable. */
    suspend fun parseAndSchedule(rawText: String): String {
        val at = Regex("at\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(rawText)
        val inM = Regex("in\\s+(\\d+)\\s*(min|mins|minute|minutes|hr|hrs|hour|hours)", RegexOption.IGNORE_CASE).find(rawText)
        val wantsTomorrow = Regex("\\b(tomorrow|tmr|tom)\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawText)
        val weekday = WEEKDAYS.entries.firstOrNull { (name, _) ->
            Regex("\\b(next\\s+)?$name\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawText)
        }
        val label = rawText
            .replace(Regex("(?i)^(please\\s+)?remind me( to| about)?\\s*"), "")
            .replace(Regex("(?i)\\b(remind me|reminder)\\b"), "")
            .trim().take(140)
        val cal = Calendar.getInstance()
        when {
            inM != null -> {
                val n = inM.groupValues[1].toInt()
                val minutes = if (inM.groupValues[2].lowercase(Locale.US).startsWith("h")) n * 60 else n
                cal.timeInMillis = System.currentTimeMillis() + minutes * 60_000L
            }
            at != null -> {
                var hour = at.groupValues[1].toInt()
                val minute = at.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
                val ap = at.groupValues[3].lowercase(Locale.US)
                if (ap == "pm" && hour < 12) hour += 12
                if (ap == "am" && hour == 12) hour = 0
                // Bare past hour ("at 8" when it is 10) -> tonight, not tomorrow morning.
                if (ap.isEmpty() && hour <= cal.get(Calendar.HOUR_OF_DAY)) hour += 12
                if (hour > 23) hour -= 24
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                when {
                    wantsTomorrow -> cal.add(Calendar.DAY_OF_YEAR, 1)
                    weekday != null -> {
                        val target = weekday.value
                        while (cal.get(Calendar.DAY_OF_WEEK) != target ||
                            cal.timeInMillis <= System.currentTimeMillis()
                        ) {
                            cal.add(Calendar.DAY_OF_YEAR, 1)
                        }
                    }
                    cal.timeInMillis <= System.currentTimeMillis() -> cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            wantsTomorrow -> {
                // "tomorrow" with no time -> default 9am so the intent survives.
                cal.add(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 9); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            }
            else -> return "" // NO_TIME
        }
        // F9: future and within 30 days.
        if (cal.timeInMillis <= System.currentTimeMillis()) return "" // PAST_TIME
        if (cal.timeInMillis > System.currentTimeMillis() + MAX_AHEAD_MS) {
            return "That's more than 30 days out — I can only hold reminders a month ahead."
        }
        val text = label.ifEmpty { "your reminder" }
        val id = dao.insert(
            ReminderEntry(
                text = text,
                fireMillis = cal.timeInMillis,
                createdMillis = System.currentTimeMillis(),
            ),
        )
        scheduleAlarm(cal.timeInMillis, text, id.toInt())
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        AppDebugServer.log("ACTION", "Reminder #$id scheduled: \"$text\" at ${fmt.format(cal.time)}")
        return "Set — I'll remind you \"$text\" ${fmt.format(cal.time)}."
    }

    /** Audit A3 boot path: re-arm every persisted reminder still in the future. */
    suspend fun restorePending() {
        val now = System.currentTimeMillis()
        dao.deleteFired(now)
        dao.pendingAfter(now).forEach { r ->
            scheduleAlarm(r.fireMillis, r.text, r.id.toInt())
        }
        AppDebugServer.log("ALARM", "Reminders restored after boot: ${dao.pendingAfter(now).size}")
    }

    private fun scheduleAlarm(atMillis: Long, text: String, requestCode: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val i = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_ID, requestCode.toLong())
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } catch (_: SecurityException) {
            // F10: exact alarm denied — inexact fallback still delivers.
            am.set(AlarmManager.RTC_WAKEUP, atMillis, pi)
            AppDebugServer.log("ACTION", "Exact alarm denied; scheduled inexact fallback")
        }
    }

    companion object {
        const val ACTION_REMINDER = "com.shiina.mobile.ACTION_REMINDER"
        const val EXTRA_TEXT = "reminder_text"
        const val EXTRA_ID = "reminder_id"
        private const val MAX_AHEAD_MS = 30L * 86_400_000L
        private val WEEKDAYS = mapOf(
            "sunday" to Calendar.SUNDAY,
            "monday" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY,
        )
    }
}

/** Fires her overlay with the reminder text at the scheduled moment. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT).orEmpty()
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1L)
        if (text.isEmpty()) return
        AppDebugServer.log("ALARM", "Reminder fired: $text")
        runCatching {
            val show = Intent(context, CharacterOverlayService::class.java).apply {
                action = CharacterOverlayService.ACTION_SHOW
                putExtra(CharacterOverlayService.EXTRA_TONE, "candid_direct")
                putExtra(CharacterOverlayService.EXTRA_MESSAGE, "Reminder: $text")
                putExtra(CharacterOverlayService.EXTRA_MODE, CharacterMode.WANDER.name)
            }
            context.startForegroundService(show)
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "Reminder overlay failed: ${e.message}")
        }
        // Delete the persisted row + count it as an acted outcome.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val container = (context.applicationContext as CompanionApp).container
                if (id >= 0) container.database.reminderDao().delete(id)
                container.memoryOutcomes.onActionExecuted()
            }
            pending.finish()
        }
    }
}