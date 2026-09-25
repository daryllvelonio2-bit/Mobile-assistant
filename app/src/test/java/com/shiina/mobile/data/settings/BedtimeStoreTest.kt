package com.shiina.mobile.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BedtimeStoreTest {

    @Before
    fun setUp() {
        // Reset BedtimeStore to default state before each test
        BedtimeStore.setBedtime(
            BedtimeStore.DEFAULT_START_HOUR,
            BedtimeStore.DEFAULT_START_MINUTE,
            BedtimeStore.DEFAULT_END_HOUR,
            BedtimeStore.DEFAULT_END_MINUTE,
        )
    }

    @Test
    fun testDefaultBedtimeValues() {
        val bedtime = BedtimeStore.getBedtime()
        assertEquals(23, bedtime.startHour)
        assertEquals(0, bedtime.startMinute)
        assertEquals(7, bedtime.endHour)
        assertEquals(0, bedtime.endMinute)

        assertEquals(23 * 60, BedtimeStore.getStartMinutes())
        assertEquals(7 * 60, BedtimeStore.getEndMinutes())
    }

    @Test
    fun testOvernightBedtimeEvaluation() {
        val bedtime = Bedtime(startHour = 23, startMinute = 0, endHour = 7, endMinute = 0)

        // Before bedtime
        assertFalse("22:59 should not be bedtime", bedtime.isBedtime(22, 59))
        assertFalse("12:00 should not be bedtime", bedtime.isBedtime(12, 0))

        // Inside bedtime
        assertTrue("23:00 should be bedtime", bedtime.isBedtime(23, 0))
        assertTrue("23:45 should be bedtime", bedtime.isBedtime(23, 45))
        assertTrue("00:00 (midnight) should be bedtime", bedtime.isBedtime(0, 0))
        assertTrue("03:15 should be bedtime", bedtime.isBedtime(3, 15))
        assertTrue("06:59 should be bedtime", bedtime.isBedtime(6, 59))

        // Exact boundary at end time (wake time)
        assertFalse("07:00 should not be bedtime", bedtime.isBedtime(7, 0))
        assertFalse("07:01 should not be bedtime", bedtime.isBedtime(7, 1))
    }

    @Test
    fun testSameDayBedtimeEvaluation() {
        // Bedtime within same day (e.g. 01:00 to 06:00)
        val bedtime = Bedtime(startHour = 1, startMinute = 0, endHour = 6, endMinute = 0)

        assertFalse("00:59 should not be bedtime", bedtime.isBedtime(0, 59))
        assertTrue("01:00 should be bedtime", bedtime.isBedtime(1, 0))
        assertTrue("03:00 should be bedtime", bedtime.isBedtime(3, 0))
        assertTrue("05:59 should be bedtime", bedtime.isBedtime(5, 59))
        assertFalse("06:00 should not be bedtime", bedtime.isBedtime(6, 0))
    }

    @Test
    fun testBedtimeStoreSettersAndSync() {
        BedtimeStore.setBedtimeStart(22, 30)
        assertEquals(22, BedtimeStore.getStartHour())
        assertEquals(30, BedtimeStore.getStartMinute())
        assertEquals(22 * 60 + 30, BedtimeStore.getStartMinutes())

        BedtimeStore.setBedtimeEnd(8, 15)
        assertEquals(8, BedtimeStore.getEndHour())
        assertEquals(15, BedtimeStore.getEndMinute())
        assertEquals(8 * 60 + 15, BedtimeStore.getEndMinutes())

        BedtimeStore.setBedtime(0, 0, 8, 0)
        val bedtime = BedtimeStore.getBedtime()
        assertEquals(0, bedtime.startHour)
        assertEquals(0, bedtime.startMinute)
        assertEquals(8, bedtime.endHour)
        assertEquals(0, bedtime.endMinute)
    }
}
