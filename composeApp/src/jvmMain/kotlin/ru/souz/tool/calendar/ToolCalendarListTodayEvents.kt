package ru.souz.tool.calendar

import ru.souz.llms.ToolInvocationMeta

import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

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

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        val calName = input.calendarName
        return bash.sh(CalendarAppleScriptCommands.listTodayEventsCommand(calName))
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}
