package ru.abledo.tool.calendar

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
                tell calendar calName
                    set todaysEvents to (every event whose start date is greater than or equal to todayStart and start date is less than todayEnd)
                    if (count of todaysEvents) is 0 then
                        return "No events found today."
                    end if
                    repeat with anEvent in todaysEvents
                        set evtTitle to summary of anEvent
                        set evtTime to time string of (start date of anEvent)
                        set output to output & "- " & evtTime & ": " & evtTitle & return
                    end repeat
                end tell
                return output
            on error
                return "Error: Calendar '" & calName & "' not found."
            end try
        end tell
EOF
    """.trimIndent()

    fun createEventCommand(calName: String, title: String, startStr: String, durationMin: Int, loc: String, desc: String): String = """
        osascript <<'EOF'
        tell application "Calendar"
            try
                set targetCal to calendar "$calName"
                set startDate to date "$startStr"
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

    fun listCalendarsCommand(): String = """
osascript <<'EOF'
tell application "Calendar"
    try
        -- Получаем имена всех календарей
        set calNames to name of every calendar
        
        set output to "Available calendars:" & return
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

