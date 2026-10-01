package com.shiina.mobile.observation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable snapshot of everything a smart contextual briefing needs. Pure data —
 * no Android types — so payload + timing-gate generation is unit testable.
 */
data class BriefingInputs(
    val hour: Int,
    val minute: Int,
    val dayOfWeek: Int,
    val batteryPercent: Int,
    val charging: Boolean,
    val screenOn: Boolean,
    val foregroundApp: String = "",
    val musicPlaying: Boolean = false,
    val calendarEvents: List<String> = emptyList(),
    val nextEventMinutesUntil: Int = -1,
    val unreadCount: Int = 0,
    val unreadSenders: List<String> = emptyList(),
)

/** Outcome of the smart timing gate: whether to check in, why, and under which rule. */
data class TimingGate(val allowed: Boolean, val label: String, val reason: String)

/**
 * Phase 1 (Smart Contextual Briefings): turns raw local device state into the
 * structured briefing payload she receives on every proactive tick, and decides
 * whether *this* moment is a relevant one to speak (morning pickup, post-work
 * transition, low-battery warning, late-night / binge guardians, learned hours).
 *
 * Everything here is pure and deterministic so the payload and the gate rules are
 * covered by plain JVM unit tests with no device.
 */
object BriefingContextBuilder {

    const val LOW_BATTERY_TRIGGER_PERCENT = 20
    const val DEFAULT_BINGE_THRESHOLD_MINUTES = 120
    /** A once-a-day window (morning pickup / post-work) re-arms after this gap. */
    const val ONCE_A_DAY_GAP_MS = 12 * 3_600_000L

    fun period(hour: Int): String = when (hour) {
        in 5..11 -> "morning_briefing"
        in 12..16 -> "afternoon_checkin"
        in 17..21 -> "evening_transition"
        else -> "late_night_guardian"
    }

    fun dayName(dayOfWeek: Int): String = when (dayOfWeek) {
        1 -> "Sunday"
        2 -> "Monday"
        3 -> "Tuesday"
        4 -> "Wednesday"
        5 -> "Thursday"
        6 -> "Friday"
        7 -> "Saturday"
        else -> "Unknown"
    }

    fun isWeekend(dayOfWeek: Int): Boolean = dayOfWeek == 1 || dayOfWeek == 7

    /**
     * Smart timing gate. Evaluated in priority order; the first matching rule wins.
     * Guardians (late night / binge) and low battery always pass so she may
     * intervene; bedtime suppresses; otherwise learned hour / learned window /
     * default active hours decide.
     */
    fun resolveTimingGate(
        inputs: BriefingInputs,
        learnedHour: Int? = null,
        activeWindow: String? = null,
        bedtime: String? = null,
        bingeMinutes: Int = 0,
        bingeThresholdMinutes: Int = DEFAULT_BINGE_THRESHOLD_MINUTES,
        lastMorningBriefMillis: Long = 0L,
        lastPostWorkBriefMillis: Long = 0L,
        nowMillis: Long,
    ): TimingGate {
        val hour = inputs.hour

        if (hour >= 23 || hour < 5) {
            return TimingGate(true, "late_night_guardian", "active use at $hour:00")
        }
        if (bingeMinutes >= bingeThresholdMinutes) {
            return TimingGate(true, "binge_scroll_guardian", "entertainment ${bingeMinutes}min this session/day")
        }
        if (inputs.batteryPercent in 1..LOW_BATTERY_TRIGGER_PERCENT && !inputs.charging) {
            return TimingGate(true, "low_battery_warning", "battery ${inputs.batteryPercent}% and not charging")
        }
        if (hour in 5..11 && nowMillis - lastMorningBriefMillis >= ONCE_A_DAY_GAP_MS) {
            return TimingGate(true, "morning_pickup", "first pickup of the day")
        }
        if (hour in 17..19 && nowMillis - lastPostWorkBriefMillis >= ONCE_A_DAY_GAP_MS) {
            return TimingGate(true, "post_work_transition", "post-work wind-down")
        }
        if (learnedHour != null && hour == learnedHour) {
            return TimingGate(true, "learned_nudge_hour", "matches her learned hour ($learnedHour:00)")
        }
        val bedHour = bedtime?.substringBefore(":")?.trim()?.toIntOrNull()
        if (bedHour != null && inBedtimeWindow(hour, bedHour)) {
            return TimingGate(false, "bedtime_suppressed", "inside learned bedtime ($bedtime)")
        }
        if (!activeWindow.isNullOrBlank()) {
            val parsed = parseWindow(activeWindow)
            if (parsed != null) {
                return if (inWindow(hour, parsed.first, parsed.second)) {
                    TimingGate(true, "active_window", "inside learned active window ($activeWindow)")
                } else {
                    TimingGate(false, "outside_active_window", "outside learned active window ($activeWindow)")
                }
            }
        }
        return if (hour in 8..22) {
            TimingGate(true, "default_active_window", "inside default 8-22 window")
        } else {
            TimingGate(false, "outside_active_hours", "outside active hours ($hour:00)")
        }
    }

    /** Structured briefing payload handed to the model / debug log. */
    fun build(inputs: BriefingInputs, gate: TimingGate): JSONObject {
        val obj = JSONObject()
            .put("period", period(inputs.hour))
            .put("day", dayName(inputs.dayOfWeek))
            .put("is_weekend", isWeekend(inputs.dayOfWeek))
            .put("clock", String.format("%02d:%02d", inputs.hour, inputs.minute))
            .put("timing_gate", gate.label)
            .put("timing_allowed", gate.allowed)
            .put("timing_reason", gate.reason)
            .put(
                "battery",
                JSONObject()
                    .put("percent", inputs.batteryPercent)
                    .put("charging", inputs.charging)
                    .put("low", inputs.batteryPercent in 1..LOW_BATTERY_TRIGGER_PERCENT),
            )
            .put(
                "calendar",
                JSONObject()
                    .put("has_events", inputs.calendarEvents.isNotEmpty())
                    .put("count", inputs.calendarEvents.size)
                    .put("next_event", inputs.calendarEvents.firstOrNull() ?: JSONObject.NULL)
                    .put("next_event_minutes_until", inputs.nextEventMinutesUntil)
                    .put("events", JSONArray(inputs.calendarEvents)),
            )
            .put(
                "notifications",
                JSONObject()
                    .put("unread_count", inputs.unreadCount)
                    .put("top_senders", JSONArray(inputs.unreadSenders)),
            )
            .put("foreground_app", inputs.foregroundApp)
            .put("music_playing", inputs.musicPlaying)
            .put("recommendation_hint", recommendationHint(inputs, gate))
        return obj
    }

    /** Human-readable block injected into the proactive model prompt. */
    fun promptBlock(inputs: BriefingInputs, gate: TimingGate): String {
        val sb = StringBuilder()
        sb.appendLine("[SMART BRIEFING CONTEXT]")
        sb.appendLine("- moment: ${period(inputs.hour)} · ${dayName(inputs.dayOfWeek)} ${String.format("%02d:%02d", inputs.hour, inputs.minute)}${if (isWeekend(inputs.dayOfWeek)) " (weekend)" else ""}")
        sb.appendLine("- timing_gate: ${gate.label} (${gate.reason})")
        sb.appendLine("- battery: ${inputs.batteryPercent}% ${if (inputs.charging) "charging" else "on battery"}${if (inputs.batteryPercent in 1..LOW_BATTERY_TRIGGER_PERCENT) " [low]" else ""}")
        if (inputs.calendarEvents.isEmpty()) {
            sb.appendLine("- calendar: no upcoming events in the next 24h")
        } else {
            val next = inputs.calendarEvents.first()
            val eta = if (inputs.nextEventMinutesUntil >= 0) " (in ${inputs.nextEventMinutesUntil} min)" else ""
            sb.appendLine("- calendar: ${inputs.calendarEvents.size} upcoming · next: $next$eta")
        }
        sb.appendLine("- unread notifications: ${inputs.unreadCount}${if (inputs.unreadSenders.isNotEmpty()) " from " + inputs.unreadSenders.joinToString(", ") else ""}")
        if (inputs.foregroundApp.isNotBlank()) sb.appendLine("- foreground_app: ${inputs.foregroundApp}")
        if (inputs.musicPlaying) sb.appendLine("- music: playing")
        sb.append("- hint: ${recommendationHint(inputs, gate)}")
        return sb.toString()
    }

    fun recommendationHint(inputs: BriefingInputs, gate: TimingGate): String = when {
        gate.label == "low_battery_warning" ->
            "Battery at ${inputs.batteryPercent}% and unplugged — a short, practical nudge to charge fits."
        gate.label == "late_night_guardian" ->
            "Late-night active use — gentle, caring push toward rest."
        gate.label == "binge_scroll_guardian" ->
            "Long entertainment stretch — fair to call out the scrolling."
        gate.label == "morning_pickup" ->
            "First pickup of the day — a brief, grounded start-of-day check-in."
        gate.label == "post_work_transition" ->
            "Day is winding down — a light transition check-in."
        inputs.calendarEvents.isNotEmpty() && inputs.nextEventMinutesUntil in 0..45 ->
            "An event is coming up soon; a timely heads-up is warranted."
        inputs.unreadCount >= 5 ->
            "${inputs.unreadCount} unread pings are waiting; offer a calm summary."
        else -> "Nothing urgent — only speak if it genuinely fits the moment."
    }

    // ---- pure helpers -------------------------------------------------------

    fun inBedtimeWindow(hour: Int, bedHour: Int): Boolean =
        if (bedHour >= 12) hour >= bedHour || hour < 7 else hour in bedHour..7

    /** Parses "09:00-22:00" → (9, 22), else null. */
    fun parseWindow(window: String): Pair<Int, Int>? {
        val parts = window.split("-")
        if (parts.size != 2) return null
        val start = parts[0].substringBefore(":").trim().toIntOrNull()
        val end = parts[1].substringBefore(":").trim().toIntOrNull()
        return if (start != null && end != null) start to end else null
    }

    fun inWindow(hour: Int, start: Int, end: Int): Boolean =
        if (start <= end) hour in start..end else hour >= start || hour <= end
}
