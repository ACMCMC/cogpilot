package fyi.acmc.cogpilot

import android.Manifest
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.PermissionChecker

/**
 * CalendarContextProvider: reads upcoming events for convo context
 * small and sloppy: just enough for prototyping; no caching yet
 */
class CalendarContextProvider(private val context: Context) {

    fun getUpcomingEvents(limit: Int = 5, windowMinutes: Int = 180): List<CalendarEvent> {
        if (PermissionChecker.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) != PermissionChecker.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val end = now + (windowMinutes * 60_000L)

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION
        )

        val selection = "${CalendarContract.Events.DTSTART} BETWEEN ? AND ?"
        val selectionArgs = arrayOf(now.toString(), end.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val events = mutableListOf<CalendarEvent>()

        val cursor: Cursor? = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        cursor?.use {
            val titleIdx = it.getColumnIndex(CalendarContract.Events.TITLE)
            val startIdx = it.getColumnIndex(CalendarContract.Events.DTSTART)
            val endIdx = it.getColumnIndex(CalendarContract.Events.DTEND)
            val locIdx = it.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)

            while (it.moveToNext() && events.size < limit) {
                val title = if (titleIdx >= 0) it.getString(titleIdx) else ""
                val start = if (startIdx >= 0) it.getLong(startIdx) else 0L
                val endTime = if (endIdx >= 0) it.getLong(endIdx) else 0L
                val location = if (locIdx >= 0) it.getString(locIdx) else null
                events.add(CalendarEvent(title, start, endTime, location))
            }
        }

        return events
    }
}

data class CalendarEvent(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val location: String?
)
