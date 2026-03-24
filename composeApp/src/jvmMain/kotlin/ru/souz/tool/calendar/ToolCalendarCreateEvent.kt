package ru.souz.tool.calendar

import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionKind
import ru.souz.tool.ToolActionValueFormatter
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ToolCalendarCreateEvent(private val bash: ToolRunBashCommand) : ToolSetup<ToolCalendarCreateEvent.Input> {
    data class Input(
        @InputParamDescription("Name of the calendar to use (default: 'Calendar' or 'Home')")
        val calendarName: String? = "Calendar",

        @InputParamDescription("Title/Summary of the event")
        val title: String,

        @InputParamDescription("REQUIRED. Strict format 'yyyy-MM-dd HH:mm:ss' (e.g. '2025-12-07 14:30:00'). Calculate based on current system time.")
        val startDateTime: String,

        @InputParamDescription("Duration of the event in minutes (default 60)")
        val durationMinutes: Int = 60,

        @InputParamDescription("Location of the event")
        val location: String? = null,

        @InputParamDescription("Description/Notes for the event")
        val description: String? = null,

        @InputParamDescription("Minutes before the event to show a reminder (e.g. 5, 10, 15, 30, 60). Null means no reminder.")
        val reminderMinutes: Int? = null,
    )

    override val name: String = "CalendarCreateEvent"
    override val description: String = "Create an event in macOS Calendar."

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

    override fun describeAction(input: Input): ToolActionDescriptor? = ToolActionDescriptor(
        kind = ToolActionKind.CREATE_CALENDAR_EVENT,
        primary = ToolActionValueFormatter.compactText(input.title),
    )

    override fun invoke(input: Input): String {
        if (input.title.isBlank()) throw BadInputException("'title' is required.")

        val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val dateObj = try {
            LocalDateTime.parse(input.startDateTime, isoFormatter)
        } catch (e: Exception) {
            return missingStartDateTimeResponse(input.startDateTime)
        }

        val safeTitle = input.title.replace("\"", "\\\"")
        val safeDesc = input.description?.replace("\"", "\\\"") ?: ""
        val safeLoc = input.location?.replace("\"", "\\\"") ?: ""
        val calName = input.calendarName ?: "Calendar"

        val result = bash.sh(
            CalendarAppleScriptCommands.createEventCommand(
                calName,
                safeTitle,
                dateObj.year,
                dateObj.monthValue,
                dateObj.dayOfMonth,
                dateObj.hour,
                dateObj.minute,
                input.durationMinutes,
                safeLoc,
                safeDesc,
                input.reminderMinutes,
            )
        )

        return result
    }

    private fun missingStartDateTimeResponse(received: String?): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        return "Error: Invalid date format received '${received}'. I STRICTLY require 'yyyy-MM-dd HH:mm:ss'. Current system time is: $now."
    }
}
