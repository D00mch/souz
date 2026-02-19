package ru.souz.tool.calendar

import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

const val APPLE_EPOCH_OFFSET_SECONDS = 978307200L
internal object CalendarAppleScriptCommands {
    fun listTodayEventsCommand(calendarName: String): String = """
        osascript <<'EOF'
        set calName to "$calendarName" 
        set output to "Events for today in " & calName & ":" & return
        set todayStart to current date
        set time of todayStart to 0
        set todayEnd to todayStart + (24 * hours)

        tell application "Calendar"
            try
                set targetCals to (calendars whose name is calName)
                
                if (count of targetCals) is 0 then
                    return "Error: Calendar '" & calName & "' not found. Check the name."
                end if
                
                set targetCal to first item of targetCals
                
        
                tell targetCal
                    set todaysEvents to (every event whose start date is greater than or equal to todayStart and start date is less than todayEnd)
                    
                    if (count of todaysEvents) is 0 then
                        return "No events found today in '" & calName & "'."
                    end if
                    
                    repeat with anEvent in todaysEvents
                        set evtTitle to summary of anEvent
                        
                        -- Получаем время. Обработка ошибок нужна, если это событие "на весь день"
                        try
                            set evtTime to time string of (start date of anEvent)
                        on error
                            set evtTime to "All Day"
                        end try
                        
                        set output to output & "- " & evtTime & ": " & evtTitle & return
                    end repeat
                end tell
                
                return output
                
            on error errMsg
                return "System Error: " & errMsg
            end try
        end tell
    EOF
    """.trimIndent()

    fun createEventCommand(
        calName: String,
        title: String,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        durationMin: Int,
        loc: String,
        desc: String
    ): String = """
osascript <<'EOF'
tell application "Calendar"
    try
        set targetCals to calendars whose name is "$calName"
        if (count of targetCals) is 0 then
            set targetCal to first calendar
        else
            set targetCal to first item of targetCals
        end if
        
        set startDate to current date
        set year of startDate to $year
        set month of startDate to $month
        set day of startDate to $day
        set time of startDate to ($hour * hours + $minute * minutes)
        set seconds of startDate to 0
        
        set endDate to startDate + ($durationMin * minutes)
        
        tell targetCal
            make new event with properties {summary:"$title", start date:startDate, end date:endDate, location:"$loc", description:"$desc"}
        end tell
        
        return "Event '$title' created successfully at " & (startDate as string)
    on error errMsg
        return "Error creating event: " & errMsg
    end try
end tell
EOF
    """.trimIndent()

    fun deleteEventCommand(calName: String, titlePart: String): String = """
        osascript <<'EOF'
        tell application "Calendar"
            try
                tell calendar "$calName"
                    set eventsToDelete to (every event whose summary contains "$titlePart")
                    if (count of eventsToDelete) is 0 then
                        return "No event found with title containing '$titlePart'."
                    end if
                    delete eventsToDelete
                    return "Deleted " & (count of eventsToDelete) & " event(s)."
                end tell
            on error errMsg
                return "Error deleting event: " & errMsg
            end try
        end tell
EOF
    """.trimIndent()

    fun listCalendarsCommand(filter: String? = null): String {
        val safeFilter = filter?.replace("\"", "\\\"") ?: ""
        return """
            osascript <<'EOF'
            set filterStr to "$safeFilter"
            tell application "Calendar"
                try
                    if filterStr is "" then
                        set calNames to name of every calendar
                    else
                        set calNames to name of every calendar whose name contains filterStr
                    end if
                    if (count of calNames) is 0 then
                        return "No calendars found."
                    end if
                    set output to "Found calendars:" & return
                    repeat with cName in calNames
                        set output to output & "- " & cName & return
                    end repeat
                    return output
                on error errMsg
                    return "Error listing calendars: " & errMsg
                end try
            end tell
            EOF
        """.trimIndent()
    }

    data class CalendarEvent(
        val title: String,
        val calendarName: String,
        val startDate: ZonedDateTime,
        val endDate: ZonedDateTime,
        val isAllDay: Boolean,
        val hasRecurrences: Boolean,
        val recurrenceFrequency: Int,
        val recurrenceInterval: Int,
        val recurrenceSpecifier: String?
    )

    fun listEventsCommand(calendarName: String, dateStr: String? = null): List<CalendarEvent> {
        val targetDate = try {
            if (dateStr.isNullOrBlank()) {
                LocalDate.now()
            } else {
                LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            }
        } catch (e: Exception) {
            LocalDate.now()
        }

        val events = mutableListOf<CalendarEvent>()
        val userHome = System.getProperty("user.home")
        val possiblePaths = listOf(
            "$userHome/Library/Group Containers/group.com.apple.calendar/Calendar.sqlitedb",
            "$userHome/Library/Calendars/Calendar.sqlitedb",
            "$userHome/Library/Calendars/Calendar Cache"
        )

        var dbFile: java.io.File? = null
        for (path in possiblePaths) {
            val file = java.io.File(path)
            if (file.exists() && file.length() > 0L) {
                dbFile = file
                break
            }
        }

        if (dbFile == null) {
            println("Error: Could not find a valid Apple Calendar database on this Mac.")
            return emptyList()
        }

        val url = "jdbc:sqlite:${dbFile.toURI()}?mode=ro"

        val query = """
        SELECT DISTINCT
            Calendar.title AS calendar,
            CalendarItem.summary AS title,
            CAST(CalendarItem.start_date AS INT) AS start_date,
            CAST(CalendarItem.end_date AS INT) AS end_date,
            CalendarItem.all_day,
            CalendarItem.has_recurrences,
            Recurrence.frequency,
            Recurrence.interval,
            Recurrence.specifier
        FROM Store
        JOIN Calendar ON Calendar.store_id = Store.rowid
        JOIN CalendarItem ON CalendarItem.calendar_id = Calendar.rowid
        LEFT OUTER JOIN Recurrence ON Recurrence.owner_id = CalendarItem.rowid
        WHERE Store.disabled IS NOT 1 
        AND Calendar.title = ?
    """.trimIndent()

        var connection: Connection? = null
        try {
            Class.forName("org.sqlite.JDBC")
            connection = DriverManager.getConnection(url)
            val statement = connection.prepareStatement(query)
            statement.setString(1, calendarName)

            val resultSet = statement.executeQuery()

            while (resultSet.next()) {
                val startZoned = convertAppleDateToZoned(resultSet.getLong("start_date"))
                val endZoned = convertAppleDateToZoned(resultSet.getLong("end_date"))

                // Обработка интервала (если null, считаем как 1)
                var interval = resultSet.getInt("interval")
                if (resultSet.wasNull() || interval == 0) interval = 1

                val event = CalendarEvent(
                    title = resultSet.getString("title") ?: "Calendar",
                    calendarName = resultSet.getString("calendar"),
                    startDate = startZoned,
                    endDate = endZoned,
                    isAllDay = resultSet.getInt("all_day") == 1,
                    hasRecurrences = resultSet.getInt("has_recurrences") == 1,
                    recurrenceFrequency = resultSet.getInt("frequency"),
                    recurrenceInterval = interval,
                    recurrenceSpecifier = resultSet.getString("specifier")
                )

                if (doesEventHappenOnDate(event, targetDate)) {
                    events.add(event)
                }
            }
        } catch (e: Exception) {
            println("DB Error: ${e.message}")
        } finally {
            connection?.close()
        }

        return events
    }

    fun doesEventHappenOnDate(event: CalendarEvent, targetDate: LocalDate): Boolean {
        val startDate = event.startDate.toLocalDate()
        val endDate = event.endDate.toLocalDate()

        if (!event.hasRecurrences) {
            return !targetDate.isBefore(startDate) && !targetDate.isAfter(endDate)
        }

        if (targetDate.isBefore(startDate)) return false

        val interval = event.recurrenceInterval

        return when (event.recurrenceFrequency) {
            1 -> {
                val daysBetween = ChronoUnit.DAYS.between(startDate, targetDate)
                daysBetween % interval == 0L
            }
            2 -> {
                val daysBetween = ChronoUnit.DAYS.between(startDate, targetDate)
                daysBetween % (interval * 7) == 0L
            }
            3 -> {
                val monthsBetween = ChronoUnit.MONTHS.between(startDate, targetDate)
                monthsBetween % interval == 0L && startDate.dayOfMonth == targetDate.dayOfMonth
            }
            4 -> {
                val yearsBetween = ChronoUnit.YEARS.between(startDate, targetDate)
                yearsBetween % interval == 0L && startDate.dayOfMonth == targetDate.dayOfMonth && startDate.month == targetDate.month
            }
            else -> false
        }
    }

    fun convertAppleDateToZoned(appleSeconds: Long): ZonedDateTime {
        val unixSeconds = appleSeconds + APPLE_EPOCH_OFFSET_SECONDS
        val instant = Instant.ofEpochSecond(unixSeconds)
        return ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
    }
}