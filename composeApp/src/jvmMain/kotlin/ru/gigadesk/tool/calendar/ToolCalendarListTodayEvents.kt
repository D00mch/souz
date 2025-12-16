package ru.gigadesk.tool.calendar

import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

class ToolCalendarListTodayEvents(private val bash: ToolRunBashCommand) : ToolSetup<ToolCalendarListTodayEvents.Input> {
    data class Input(
        @InputParamDescription("Name of the calendar to use. Use default if user doesn't want to specify.")
        val calendarName: String = "",
    )

    override val name: String = "CalendarListTodayEvents"
    override val description: String = "List today's events from the specified calendar."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Какие встречи у меня сегодня?",
            params = mapOf(
                "calendarName" to "Calendar",
            )
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input): String {
        val calName = input.calendarName
        return bash.sh(CalendarAppleScriptCommands.listTodayEventsCommand(calName))
    }
}
