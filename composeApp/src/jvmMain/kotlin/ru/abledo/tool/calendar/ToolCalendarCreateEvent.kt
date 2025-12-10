package ru.abledo.tool.calendar

import ru.abledo.tool.BadInputException
import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.ToolSetup
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ToolCalendarCreateEvent(private val bash: ToolRunBashCommand) : ToolSetup<ToolCalendarCreateEvent.Input> {
    data class Input(
        @InputParamDescription("Name of the calendar to use (default: 'Calendar' or 'Home')")
        val calendarName: String? = "Calendar",

        @InputParamDescription("Title/Summary of the event")
        val title: String,

        @InputParamDescription("REQUIRED for create_event. Absolute date-time string (e.g. '2025-12-07 14:00:00'). Calculate based on current time.")
        val startDateTime: String? = null,

        @InputParamDescription("Duration of the event in minutes (default 60)")
        val durationMinutes: Int? = 60,

        @InputParamDescription("Location of the event")
        val location: String? = null,

        @InputParamDescription("Description/Notes for the event")
        val description: String? = null,
    )

    override val name: String = "CalendarCreateEvent"
    override val description: String = "Create an event in macOS Calendar. Use absolute 'startDateTime' based on current user time."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Создай встречу 'Созвон' завтра в 10 утра",
            params = mapOf(
                "calendarName" to "Calendar",
                "title" to "Созвон",
                "startDateTime" to "2025-12-08 10:00:00",
                "durationMinutes" to 60,
            )
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input): String {
        if (input.title.isBlank()) throw BadInputException("'title' is required.")
        val startDateTime = input.startDateTime
            ?: return missingStartDateTimeResponse()

        val safeTitle = input.title.replace("\"", "\\\"")
        val safeDesc = input.description?.replace("\"", "\\\"") ?: ""
        val safeLoc = input.location?.replace("\"", "\\\"") ?: ""
        val calName = input.calendarName ?: "Calendar"

        val result = bash.sh(
            CalendarAppleScriptCommands.createEventCommand(
                calName,
                safeTitle,
                startDateTime,
                input.durationMinutes ?: 60,
                safeLoc,
                safeDesc,
            )
        )

        if (result.contains("Invalid date and time")) {
            return "Error from macOS: Invalid date format in 'startDateTime'. Please use 'YYYY-MM-DD HH:mm:ss' or format compatible with local system settings."
        }

        return result
    }

    private fun missingStartDateTimeResponse(): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        return "Error: 'startDateTime' argument is missing. The user provided relative time, but I need absolute format 'YYYY-MM-DD HH:mm:ss'. Current system time is: $now. Please calculate the target date-time and retry."
    }
}
