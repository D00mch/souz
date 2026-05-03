package ru.souz.tool.calendar

import ru.souz.llms.ToolInvocationMeta

import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.ToolSetup

class ToolCalendarDeleteEvent(private val bash: ToolRunBashCommand) : ToolSetup<ToolCalendarDeleteEvent.Input> {
    data class Input(
        @InputParamDescription("Name of the calendar to use (default: 'Calendar' or 'Home')")
        val calendarName: String = "Calendar",

        @InputParamDescription("Title/Summary of the event")
        val title: String,
    )

    override val name: String = "CalendarDeleteEvent"
    override val description: String = "Delete events whose title contains the given text from the specified calendar."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Удалить встречу 'Созвон'",
            params = mapOf(
                "calendarName" to "Calendar",
                "title" to "Созвон",
            )
        ),
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Operation status")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String {
        if (input.title.isBlank()) throw BadInputException("'title' is required to delete an event.")
        val calName = input.calendarName
        val escapedTitle = input.title.replace("\"", "\\\"")
        return bash.sh(CalendarAppleScriptCommands.deleteEventCommand(calName, escapedTitle))
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}
