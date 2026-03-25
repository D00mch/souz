package ru.souz.tool.calendar

import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolActionDescriptor
import ru.souz.tool.ToolActionKind
import ru.souz.tool.ToolActionValueFormatter
import ru.souz.tool.ToolSetup
import ru.souz.tool.calendar.CalendarAppleScriptCommands.listEventsCommand
import java.time.format.DateTimeFormatter

class ToolCalendarListEvents : ToolSetup<ToolCalendarListEvents.Input> {

    data class Input(
        @InputParamDescription("Name of the calendar (e.g. 'Home', 'Work'). Leave empty to try finding default.")
        val calendarName: String,

        @InputParamDescription("Date to list events for, in format 'YYYY-MM-DD'. If empty, defaults to today.")
        val date: String = ""
    )

    override val name: String = "CalendarListEvents"
    override val description: String = "List events from a specific calendar for a specific date (or today)."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Какие встречи у меня сегодня?",
            params = mapOf(
                "calendarName" to "Calendar",
                "date" to ""
            )
        ),
        FewShotExample(
            request = "Что у меня запланировано на 20 декабря 2025 года в рабочем календаре?",
            params = mapOf(
                "calendarName" to "Work",
                "date" to "2025-12-20"
            )
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "List of events with times")
        )
    )

    override fun describeAction(input: Input): ToolActionDescriptor? =
        ToolActionKind.LIST_CALENDAR_EVENTS.textAction(input.date)

    override fun invoke(input: Input): String {
        val calName = input.calendarName.ifBlank { "Calendar" }

        val targetDateStr = input.date.ifBlank { null }

        val events = listEventsCommand(calName, targetDateStr)

        if (events.isEmpty()) {
            return "No events found for this date in calendar '$calName'."
        }

        val displayDate = targetDateStr ?: "today"
        val outputBuilder = StringBuilder("Events for $displayDate in calendar '$calName':\n")

        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        for (event in events) {
            val timeStr = if (event.isAllDay) {
                "[All Day]"
            } else {
                event.startDate.format(timeFormatter)
            }
            outputBuilder.append("- $timeStr: ${event.title}\n")
        }

        return outputBuilder.toString()
    }
}

fun main() {
    val tool = ToolCalendarListEvents()
    println(tool.invoke(ToolCalendarListEvents.Input("lovkikisa@gmail.com", "2026-02-20")))
}
