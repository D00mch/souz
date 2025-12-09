package ru.abledo.tool.desktop

import ru.abledo.tool.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ToolCalendar(private val bash: ToolRunBashCommand) : ToolSetup<ToolCalendar.Input> {

    enum class CalendarAction {
        list_today_events,
        create_event,
        delete_event_by_title
    }

    data class Input(
        @InputParamDescription("Action to perform with Calendar app")
        val action: CalendarAction,

        @InputParamDescription("Name of the calendar to use (default: 'Calendar' or 'Home')")
        val calendarName: String? = "Calendar",

        @InputParamDescription("Title/Summary of the event")
        val title: String? = null,

        // Улучшили описание, чтобы модели было понятнее
        @InputParamDescription("REQUIRED for create_event. Absolute date-time string (e.g. '2025-12-07 14:00:00'). Calculate based on current time.")
        val startDateTime: String? = null,

        @InputParamDescription("Duration of the event in minutes (default 60)")
        val durationMinutes: Int? = 60,

        @InputParamDescription("Location of the event")
        val location: String? = null,

        @InputParamDescription("Description/Notes for the event")
        val description: String? = null
    )

    override val name: String = "CalendarAppTool"
    override val description: String = "Interact with macOS Calendar. To create event, YOU MUST calculate absolute 'startDateTime' based on current user time."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Какие встречи у меня сегодня?",
            params = mapOf(
                "action" to CalendarAction.list_today_events,
                "calendarName" to "Calendar"
            )
        ),
        FewShotExample(
            request = "Создай встречу 'Созвон' завтра в 10 утра",
            params = mapOf(
                "action" to CalendarAction.create_event,
                "title" to "Созвон",
                "startDateTime" to "2025-12-08 10:00:00", // Пример показывает, что дата должна быть вычислена
                "durationMinutes" to 60
            )
        )
    )

    override val returnParameters = ReturnParameters(
        type = "string",
        properties = emptyMap()
    )

    override fun invoke(input: Input): String {
        val calName = input.calendarName ?: "Calendar"

        return when (input.action) {
            CalendarAction.list_today_events -> {
                bash.sh(listTodayEventsCommand(calName))
            }

            CalendarAction.create_event -> {
                if (input.title == null) {
                    return "Error: 'title' is required."
                }

                if (input.startDateTime == null) {
                    // Получаем текущее время системы, чтобы помочь модели
                    val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    return "Error: 'startDateTime' argument is missing. " +
                            "The user provided relative time, but I need absolute format 'YYYY-MM-DD HH:mm:ss'. " +
                            "Current system time is: $now. Please calculate the target date-time and retry."
                }

                val safeTitle = input.title.replace("\"", "\\\"")
                val safeDesc = input.description?.replace("\"", "\\\"") ?: ""
                val safeLoc = input.location?.replace("\"", "\\\"") ?: ""

                // Пробуем выполнить команду
                val result = bash.sh(createEventCommand(
                    calName,
                    safeTitle,
                    input.startDateTime,
                    input.durationMinutes ?: 60,
                    safeLoc,
                    safeDesc
                ))

                if (result.contains("Invalid date and time")) {
                    return "Error from macOS: Invalid date format in 'startDateTime'. Please use 'YYYY-MM-DD HH:mm:ss' or format compatible with local system settings."
                }

                result
            }

            CalendarAction.delete_event_by_title -> {
                if (input.title == null) return "Error: 'title' is required to delete an event."
                bash.sh(deleteEventCommand(calName, input.title.replace("\"", "\\\"")))
            }
        }
    }

    private fun listTodayEventsCommand(calendarName: String): String = """
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

    private fun createEventCommand(calName: String, title: String, startStr: String, durationMin: Int, loc: String, desc: String): String = """
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

    private fun deleteEventCommand(calName: String, titlePart: String): String = """
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
}