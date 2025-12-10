package ru.abledo.tool.calendar

import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.ToolSetup

class ToolCalendarListTodayEvents(private val bash: ToolRunBashCommand) : ToolSetup<ToolCalendarListTodayEvents.Input> {
    data class Input(
        @InputParamDescription("Name of the calendar to use (default: 'Calendar' or 'Home')")
        val calendarName: String? = "Calendar",
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
        val calName = input.calendarName ?: "Calendar"
        return bash.sh(CalendarAppleScriptCommands.listTodayEventsCommand(calName))
    }
}
