package com.shiina.mobile.data.settings

import java.util.Calendar

/**
 * Immutable bedtime window representation.
 *
 * @property startHour Hour (0..23) when bedtime begins (default 23).
 * @property startMinute Minute (0..59) when bedtime begins (default 0).
 * @property endHour Hour (0..23) when bedtime ends / wake time (default 7).
 * @property endMinute Minute (0..59) when bedtime ends / wake time (default 0).
 */
data class Bedtime(
    val startHour: Int = BedtimeStore.DEFAULT_START_HOUR,
    val startMinute: Int = BedtimeStore.DEFAULT_START_MINUTE,
    val endHour: Int = BedtimeStore.DEFAULT_END_HOUR,
    val endMinute: Int = BedtimeStore.DEFAULT_END_MINUTE,
) {
    /** Start time expressed as minutes since midnight (0..1439). */
    val startMinutesOfDay: Int get() = startHour * 60 + startMinute

    /** End time expressed as minutes since midnight (0..1439). */
    val endMinutesOfDay: Int get() = endHour * 60 + endMinute

    /**
     * Checks if a minute-of-day (0..1439) falls within the bedtime window.
     * Correctly handles windows that cross midnight (e.g., 23:00 -> 07:00).
     */
    fun isBedtime(minutesSinceMidnight: Int): Boolean {
        val s = startMinutesOfDay
        val e = endMinutesOfDay
        return if (s <= e) {
            minutesSinceMidnight in s until e
        } else {
            // Overnight window: e.g. 23:00 (1380) to 07:00 (420)
            minutesSinceMidnight >= s || minutesSinceMidnight < e
        }
    }

    /**
     * Checks if a specific hour and minute falls within the bedtime window.
     */
    fun isBedtime(hour: Int, minute: Int): Boolean = isBedtime(hour * 60 + minute)

    /**
     * Checks if the current wall-clock time is currently within bedtime.
     */
    fun isBedtimeNow(): Boolean {
        val cal = Calendar.getInstance()
        return isBedtime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}

/**
 * Process-wide DI-free singleton for bedtime settings.
 * Can be read directly by [com.shiina.mobile.action.ShiinaAccessibilityService] (which has no DI)
 * and prompt formatters.
 *
 * Kept in sync with [SettingsRepository] DataStore writes.
 */
object BedtimeStore {
    const val DEFAULT_START_HOUR = 23
    const val DEFAULT_START_MINUTE = 0
    const val DEFAULT_END_HOUR = 7
    const val DEFAULT_END_MINUTE = 0

    @Volatile
    private var currentBedtime = Bedtime(
        startHour = DEFAULT_START_HOUR,
        startMinute = DEFAULT_START_MINUTE,
        endHour = DEFAULT_END_HOUR,
        endMinute = DEFAULT_END_MINUTE,
    )

    fun getBedtime(): Bedtime = currentBedtime

    fun getStartHour(): Int = currentBedtime.startHour
    fun getStartMinute(): Int = currentBedtime.startMinute
    fun getEndHour(): Int = currentBedtime.endHour
    fun getEndMinute(): Int = currentBedtime.endMinute

    fun getStartMinutes(): Int = currentBedtime.startMinutesOfDay
    fun getEndMinutes(): Int = currentBedtime.endMinutesOfDay

    fun setBedtime(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int) {
        currentBedtime = Bedtime(
            startHour = startHour,
            startMinute = startMinute,
            endHour = endHour,
            endMinute = endMinute,
        )
    }

    fun setBedtimeStart(hour: Int, minute: Int) {
        currentBedtime = currentBedtime.copy(startHour = hour, startMinute = minute)
    }

    fun setBedtimeEnd(hour: Int, minute: Int) {
        currentBedtime = currentBedtime.copy(endHour = hour, endMinute = minute)
    }

    fun isBedtime(minutesSinceMidnight: Int): Boolean = currentBedtime.isBedtime(minutesSinceMidnight)

    fun isBedtime(hour: Int, minute: Int): Boolean = currentBedtime.isBedtime(hour, minute)

    fun isBedtimeNow(): Boolean = currentBedtime.isBedtimeNow()
}
