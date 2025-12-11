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
            -- Fallback: если не нашли по имени, пробуем "Calendar" или просто первый попавшийся
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
}

