package com.example.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CalEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val calendarName: String
)

class DeviceCalendarEngine(private val context: Context) {

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )

        fun hasPermissions(context: Context): Boolean = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        fun requiredPermissions(): Array<String> = REQUIRED_PERMISSIONS
        val DATE_FMT = SimpleDateFormat("EEE, d MMM · h:mm a", Locale.US)
    }

    fun isAvailable(): Boolean {
        return try {
            hasPermissions(context)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getUpcomingEvents(daysAhead: Int = 7, limit: Int = 10): List<CalEvent>? =
        withContext(Dispatchers.IO) {
            if (!hasPermissions(context)) return@withContext null
            return@withContext try {
                val now = System.currentTimeMillis()
                val endOfWindow = now + daysAhead.toLong() * 24L * 60L * 60L * 1000L

                val projection = arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Instances.ALL_DAY
                )

                val events = mutableListOf<CalEvent>()
                context.contentResolver.query(
                    CalendarContract.Instances.CONTENT_URI,
                    projection,
                    "${CalendarContract.Instances.BEGIN} >= ? AND ${CalendarContract.Instances.BEGIN} <= ?",
                    arrayOf(now.toString(), endOfWindow.toString()),
                    "${CalendarContract.Instances.BEGIN} ASC"
                )?.use { cursor ->
                    while (cursor.moveToNext() && events.size < limit) {
                        val title = cursor.getString(0) ?: "(No title)"
                        val begin = cursor.getLong(1)
                        val end = cursor.getLong(2)
                        val calName = cursor.getString(3) ?: "Local"
                        events.add(CalEvent(title, begin, end, calName))
                    }
                }
                events
            } catch (e: Exception) {
                null
            }
        }

    suspend fun createEvent(
        title: String,
        startMillis: Long,
        durationMinutes: Long = 30,
        allDay: Boolean = false
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!hasPermissions(context)) {
            return@withContext Pair(false, "PERMISSION_MISSING")
        }
        return@withContext try {
            val calId = getDefaultCalendarId()
                ?: return@withContext Pair(false, "NO_CALENDAR_ACCOUNT")

            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMillis)
                if (allDay) {
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.DTEND, startMillis + 24L * 60L * 60L * 1000L)
                } else {
                    put(CalendarContract.Events.DTEND, startMillis + durationMinutes * 60_000L)
                }
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) Pair(true, uri.lastPathSegment ?: "created")
            else Pair(false, "INSERT_FAILED")
        } catch (e: SecurityException) {
            Pair(false, "PERMISSION_MISSING")
        } catch (e: Exception) {
            Pair(false, "ERROR: ${e.message}")
        }
    }

    private fun getDefaultCalendarId(): Long? = try {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }

    suspend fun selfTest(): String = withContext(Dispatchers.IO) {
        if (!hasPermissions(context)) return@withContext "Needs calendar permission - ask AUREX for your agenda to trigger the grant prompt"
        val count = getUpcomingEvents(daysAhead = 30, limit = 3)
        return@withContext when {
            count == null -> "Could not read the calendar provider"
            count.isEmpty() -> "Connected - calendar readable, no upcoming events found"
            else -> "Connected - ${count.size} upcoming event(s) found, next: \"${count.first().title}\""
        }
    }

    fun formatEvents(events: List<CalEvent>): String {
        if (events.isEmpty()) return "Your calendar is clear for the next 7 days."
        val sb = StringBuilder("Here is what is coming up:\n")
        var lastDay = ""
        for (e in events) {
            val day = SimpleDateFormat("EEEE, d MMMM", Locale.US).format(Date(e.beginMillis))
            if (day != lastDay) {
                sb.append("\n").append(day).append("\n")
                lastDay = day
            }
            sb.append("- ").append(e.title)
            if (!isAllDay(e)) {
                sb.append(" at ").append(SimpleDateFormat("h:mm a", Locale.US).format(Date(e.beginMillis)))
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    private fun isAllDay(e: CalEvent): Boolean {
        val beginCal = Calendar.getInstance().apply { timeInMillis = e.beginMillis }
        val endCal = Calendar.getInstance().apply { timeInMillis = e.endMillis }
        return (endCal.timeInMillis - beginCal.timeInMillis) % (24L * 60L * 60L * 1000L) == 0L &&
                beginCal.get(Calendar.HOUR_OF_DAY) == 0
    }
}
