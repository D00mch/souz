package ru.gigadesk.tool.calendar

import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

class ToolCalendarListEvents(private val bash: ToolRunBashCommand) : ToolSetup<ToolCalendarListEvents.Input> {

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
                "date" to "2025-10-05"
            )
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "List of events with times")
        )
    )

    override fun invoke(input: Input): String {
        val calName = if (input.calendarName.isBlank()) "Calendar" else input.calendarName

        val targetDate = if (input.date.isBlank()) null else input.date

        return bash.sh(CalendarAppleScriptCommands.listEventsCommand(calName, targetDate))
    }
}

fun main() {
    val tool = ToolCalendarListEvents(ToolRunBashCommand)
    println(tool.invoke(ToolCalendarListEvents.Input("qwerty@gmail.com", "2025-12-20")))
}