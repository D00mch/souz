package ru.souz.tool.calendar

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

    fun listEventsCommand(calendarName: String, dateStr: String? = null): String {
        // Если дата не передана, скрипт будет использовать текущую
        val targetDateLogic = if (dateStr.isNullOrBlank()) {
            "set targetDate to current date"
        } else {
            try {
                val parts = dateStr.split("-")
                val y = parts[0].toInt()
                val m = parts[1].toInt()
                val d = parts[2].toInt()
                """
                set targetDate to current date
                set year of targetDate to $y
                set month of targetDate to $m
                set day of targetDate to $d
                """.trimIndent()
            } catch (e: Exception) {
                "set targetDate to current date"
            }
        }

        return """
        osascript <<'EOF'
        set calName to "$calendarName"
        
        $targetDateLogic
        
        set time of targetDate to 0
        set dayStart to targetDate
        set dayEnd to dayStart + (24 * hours)
        
        set dateString to (year of dayStart as string) & "-" & (month of dayStart as integer) & "-" & (day of dayStart as integer)
        set output to "Events for " & dateString & " in calendar '" & calName & "':" & return
        
        tell application "Calendar"
            try
                set targetCals to (calendars whose name is calName)
                if (count of targetCals) is 0 then
                    return "Error: Calendar '" & calName & "' not found."
                end if
                set targetCal to first item of targetCals
        
                tell targetCal
                    set foundEvents to (every event whose start date is greater than or equal to dayStart and start date is less than dayEnd)
                    
                    if (count of foundEvents) is 0 then
                        return "No events found for this date."
                    end if
                    
                    repeat with anEvent in foundEvents
                        set isAllDay to allday event of anEvent
                        set evtTitle to summary of anEvent
                        
                        if isAllDay then
                            set timeStr to "[All Day]"
                        else
                            set sDate to start date of anEvent
                            set timeStr to time string of sDate
                        end if
                        
                        set output to output & "- " & timeStr & ": " & evtTitle & return
                    end repeat
                end tell
                
                return output
                
            on error errMsg
                return "System Error: " & errMsg
            end try
        end tell
EOF
        """.trimIndent()
    }
}