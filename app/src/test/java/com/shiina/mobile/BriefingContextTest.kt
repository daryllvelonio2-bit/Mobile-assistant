package com.shiina.mobile

import com.shiina.mobile.observation.BriefingContextBuilder
import com.shiina.mobile.observation.BriefingInputs
import com.shiina.mobile.observation.DigestNotification
import com.shiina.mobile.observation.NotificationDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Phase 1 verification: structured briefing payload generation and the smart
 * timing gates. Pure JVM — no device, no Android framework beyond org.json.
 */
class BriefingContextTest {

    /** A realistic "now" so the once-a-day markers behave as on-device. */
    private val now = 1_700_000_000_000L

    private fun inputs(
        hour: Int = 9,
        minute: Int = 30,
        dayOfWeek: Int = Calendar.WEDNESDAY,
        battery: Int = 72,
        charging: Boolean = false,
        screenOn: Boolean = true,
        events: List<String> = emptyList(),
        minutesUntil: Int = -1,
        unread: Int = 0,
        senders: List<String> = emptyList(),
    ) = BriefingInputs(
        hour = hour,
        minute = minute,
        dayOfWeek = dayOfWeek,
        batteryPercent = battery,
        charging = charging,
        screenOn = screenOn,
        foregroundApp = "com.whatsapp",
        musicPlaying = false,
        calendarEvents = events,
        nextEventMinutesUntil = minutesUntil,
        unreadCount = unread,
        unreadSenders = senders,
    )

    // ---- payload generation -------------------------------------------------

    @Test
    fun testBriefingPayloadIncludesCalendarBatteryAndNotifications() {
        val i = inputs(
            hour = 9, minute = 5, battery = 18,
            events = listOf("09:30 Team standup @ Zoom", "14:00 Dentist"),
            minutesUntil = 25,
            unread = 7, senders = listOf("WhatsApp (5)", "Gmail (2)"),
        )
        val payload = BriefingContextBuilder.build(
            i,
            BriefingContextBuilder.resolveTimingGate(i, nowMillis = now),
        )

        assertEquals("morning_briefing", payload.getString("period"))
        assertEquals("Wednesday", payload.getString("day"))
        assertFalse(payload.getBoolean("is_weekend"))
        assertEquals("09:05", payload.getString("clock"))

        val battery = payload.getJSONObject("battery")
        assertEquals(18, battery.getInt("percent"))
        assertFalse(battery.getBoolean("charging"))
        assertTrue("18% must be flagged low", battery.getBoolean("low"))

        val calendar = payload.getJSONObject("calendar")
        assertTrue(calendar.getBoolean("has_events"))
        assertEquals(2, calendar.getInt("count"))
        assertEquals("09:30 Team standup @ Zoom", calendar.getString("next_event"))
        assertEquals(25, calendar.getInt("next_event_minutes_until"))
        assertEquals(2, calendar.getJSONArray("events").length())

        val notif = payload.getJSONObject("notifications")
        assertEquals(7, notif.getInt("unread_count"))
        assertEquals(2, notif.getJSONArray("top_senders").length())

        // Low battery outranks the morning pickup and must pass so she can warn.
        assertEquals("low_battery_warning", payload.getString("timing_gate"))
        assertTrue(payload.getString("recommendation_hint").contains("Battery"))
    }

    @Test
    fun testBriefingPayloadEmptyCalendarIsHonest() {
        val i = inputs()
        val payload = BriefingContextBuilder.build(
            i, BriefingContextBuilder.resolveTimingGate(i, nowMillis = now),
        )
        val calendar = payload.getJSONObject("calendar")
        assertFalse(calendar.getBoolean("has_events"))
        assertEquals(0, calendar.getInt("count"))
        assertEquals(-1, calendar.getInt("next_event_minutes_until"))
    }

    @Test
    fun testPeriodBoundaries() {
        assertEquals("morning_briefing", BriefingContextBuilder.period(5))
        assertEquals("morning_briefing", BriefingContextBuilder.period(11))
        assertEquals("afternoon_checkin", BriefingContextBuilder.period(12))
        assertEquals("afternoon_checkin", BriefingContextBuilder.period(16))
        assertEquals("evening_transition", BriefingContextBuilder.period(17))
        assertEquals("evening_transition", BriefingContextBuilder.period(21))
        assertEquals("late_night_guardian", BriefingContextBuilder.period(22))
        assertEquals("late_night_guardian", BriefingContextBuilder.period(3))
    }

    @Test
    fun testDayNameAndWeekend() {
        assertEquals("Sunday", BriefingContextBuilder.dayName(Calendar.SUNDAY))
        assertEquals("Saturday", BriefingContextBuilder.dayName(Calendar.SATURDAY))
        assertTrue(BriefingContextBuilder.isWeekend(Calendar.SUNDAY))
        assertTrue(BriefingContextBuilder.isWeekend(Calendar.SATURDAY))
        assertFalse(BriefingContextBuilder.isWeekend(Calendar.MONDAY))
    }

    // ---- smart timing gates -------------------------------------------------

    @Test
    fun testLateNightGuardianAlwaysPasses() {
        val gate = BriefingContextBuilder.resolveTimingGate(inputs(hour = 2), nowMillis = now)
        assertTrue(gate.allowed)
        assertEquals("late_night_guardian", gate.label)
    }

    @Test
    fun testMorningPickupPassesOnceThenFallsThrough() {
        val first = BriefingContextBuilder.resolveTimingGate(inputs(hour = 7), nowMillis = now)
        assertTrue(first.allowed)
        assertEquals("morning_pickup", first.label)

        // Same morning, already briefed a minute ago -> falls through to the default window.
        val second = BriefingContextBuilder.resolveTimingGate(
            inputs(hour = 8),
            lastMorningBriefMillis = now - 60_000L,
            nowMillis = now,
        )
        assertTrue(second.allowed)
        assertEquals("default_active_window", second.label)
    }

    @Test
    fun testPostWorkTransitionPasses() {
        val gate = BriefingContextBuilder.resolveTimingGate(inputs(hour = 18), nowMillis = now)
        assertTrue(gate.allowed)
        assertEquals("post_work_transition", gate.label)
    }

    @Test
    fun testLowBatteryTriggersWhenUnpluggedButNotWhileCharging() {
        val unplugged = BriefingContextBuilder.resolveTimingGate(
            inputs(hour = 14, battery = 15, charging = false), nowMillis = now,
        )
        assertTrue(unplugged.allowed)
        assertEquals("low_battery_warning", unplugged.label)

        val charging = BriefingContextBuilder.resolveTimingGate(
            inputs(hour = 14, battery = 15, charging = true), nowMillis = now,
        )
        assertTrue(charging.allowed)
        assertEquals("default_active_window", charging.label)
    }

    @Test
    fun testBingeScrollGuardianPasses() {
        val gate = BriefingContextBuilder.resolveTimingGate(
            inputs(hour = 15), bingeMinutes = 150, nowMillis = now,
        )
        assertTrue(gate.allowed)
        assertEquals("binge_scroll_guardian", gate.label)
    }

    @Test
    fun testLearnedNudgeHourPasses() {
        val gate = BriefingContextBuilder.resolveTimingGate(
            inputs(hour = 10), learnedHour = 10,
            lastMorningBriefMillis = now - 60_000L, // suppress the morning-pickup shortcut
            nowMillis = now,
        )
        assertTrue(gate.allowed)
        assertEquals("learned_nudge_hour", gate.label)
    }

    @Test
    fun testBedtimeSuppression() {
        val gate = BriefingContextBuilder.resolveTimingGate(
            inputs(hour = 22), bedtime = "21:00",
            nowMillis = now,
        )
        assertFalse(gate.allowed)
        assertEquals("bedtime_suppressed", gate.label)
    }

    @Test
    fun testLearnedActiveWindowBothDirections() {
        val outside = BriefingContextBuilder.resolveTimingGate(
            inputs(hour = 7), activeWindow = "09:00-22:00",
            lastMorningBriefMillis = now - 60_000L,
            nowMillis = now,
        )
        assertFalse(outside.allowed)
        assertEquals("outside_active_window", outside.label)

        val inside = BriefingContextBuilder.resolveTimingGate(
            inputs(hour = 13), activeWindow = "09:00-22:00",
            nowMillis = now,
        )
        assertTrue(inside.allowed)
        assertEquals("active_window", inside.label)
    }

    @Test
    fun testOvernightActiveWindowWraps() {
        val gate = BriefingContextBuilder.resolveTimingGate(
            inputs(hour = 21, battery = 60), activeWindow = "20:00-04:00",
            nowMillis = now,
        )
        assertTrue(gate.allowed)
        assertEquals("active_window", gate.label)
    }

    @Test
    fun testOutsideActiveHoursBlocked() {
        val gate = BriefingContextBuilder.resolveTimingGate(
            inputs(hour = 6, battery = 80),
            lastMorningBriefMillis = now - 60_000L, // suppress morning pickup
            nowMillis = now,
        )
        assertFalse(gate.allowed)
        assertEquals("outside_active_hours", gate.label)
    }

    @Test
    fun testPromptBlockIsStructuredAndGrounded() {
        val i = inputs(
            hour = 9, battery = 60,
            events = listOf("09:30 Team standup"), minutesUntil = 20,
            unread = 6, senders = listOf("WhatsApp (4)"),
        )
        val block = BriefingContextBuilder.promptBlock(
            i, BriefingContextBuilder.resolveTimingGate(i, nowMillis = now),
        )
        assertTrue(block.startsWith("[SMART BRIEFING CONTEXT]"))
        assertTrue(block.contains("timing_gate: morning_pickup"))
        assertTrue(block.contains("battery: 60%"))
        assertTrue(block.contains("next: 09:30 Team standup (in 20 min)"))
        assertTrue(block.contains("unread notifications: 6 from WhatsApp (4)"))
    }

    // ---- notification digest ------------------------------------------------

    @Test
    fun testNotificationDigestCountsAndGroupsSenders() {
        NotificationDigest.clear()
        val t = System.currentTimeMillis()
        NotificationDigest.onPosted(DigestNotification("k1", "com.whatsapp", "WhatsApp", "A", "hi", t))
        NotificationDigest.onPosted(DigestNotification("k2", "com.whatsapp", "WhatsApp", "B", "yo", t))
        NotificationDigest.onPosted(DigestNotification("k3", "com.google.android.gm", "Gmail", "C", "mail", t - 60_000L))

        assertEquals(3, NotificationDigest.unreadCount())
        assertEquals("WhatsApp (2)", NotificationDigest.topSenders().first())

        NotificationDigest.onRemoved("k1")
        assertEquals(2, NotificationDigest.unreadCount())
        NotificationDigest.clear()
    }

    @Test
    fun testNotificationDigestPrunesStaleEntries() {
        NotificationDigest.clear()
        val stale = System.currentTimeMillis() - 13 * 3_600_000L
        NotificationDigest.onPosted(DigestNotification("old", "pkg", "App", "t", "x", stale))
        assertEquals(0, NotificationDigest.unreadCount())
        NotificationDigest.clear()
    }
}
