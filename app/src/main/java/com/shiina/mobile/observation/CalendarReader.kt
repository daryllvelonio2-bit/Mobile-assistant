package com.shiina.mobile.observation

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.shiina.mobile.debug.AppDebugServer

/** One upcoming calendar event, reduced to what a briefing needs. */
data class CalendarEvent(
    val title: String,
    val beginMillis: Long,
    val allDay: Boolean,
    val location: String = "",
)

/**
 * Phase 1 (Smart Contextual Briefings): read-only calendar access for the
 * proactive briefing context. Degrades to an empty list whenever READ_CALENDAR
 * is not granted or no calendar provider exists — never throws, never blocks
 * the proactive loop.
 */
class CalendarReader(private val context: Context) {

    fun hasPermission(): Boolean = runCatching {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Events starting between now and [hoursAhead] from now, soonest first. */
    fun upcomingEvents(hoursAhead: Int = 24, limit: Int = 5): List<CalendarEvent> {
        if (!hasPermission()) {
            AppDebugServer.log("BRIEFING", "Calendar skipped: READ_CALENDAR not granted")
            return emptyList()
        }
        return runCatching {
            val begin = System.currentTimeMillis()
            val end = begin + hoursAhead * ONE_HOUR_MS
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
            )
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, begin)
            ContentUris.appendId(builder, end)
            val out = mutableListOf<CalendarEvent>()
            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                while (cursor.moveToNext() && out.size < limit) {
                    val title = cursor.getString(titleIdx)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    out.add(
                        CalendarEvent(
                            title = title,
                            beginMillis = cursor.getLong(beginIdx),
                            allDay = cursor.getInt(allDayIdx) == 1,
                            location = cursor.getString(locIdx)?.trim().orEmpty(),
                        ),
                    )
                }
            }
            out
        }.onFailure { e ->
            AppDebugServer.log("BRIEFING", "Calendar query failed: ${e.message}")
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val ONE_HOUR_MS = 3_600_000L
    }
}
