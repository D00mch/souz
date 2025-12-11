package ru.abledo.tool.calendar

import ru.abledo.tool.BadInputException
import ru.abledo.tool.FewShotExample
import ru.abledo.tool.InputParamDescription
import ru.abledo.tool.ReturnParameters
import ru.abledo.tool.ReturnProperty
import ru.abledo.tool.ToolRunBashCommand
import ru.abledo.tool.ToolSetup

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

    override fun invoke(input: Input): String {
        if (input.title.isBlank()) throw BadInputException("'title' is required to delete an event.")
        val calName = input.calendarName ?: "Calendar"
        val escapedTitle = input.title.replace("\"", "\\\"")
        return bash.sh(CalendarAppleScriptCommands.deleteEventCommand(calName, escapedTitle))
    }
}
