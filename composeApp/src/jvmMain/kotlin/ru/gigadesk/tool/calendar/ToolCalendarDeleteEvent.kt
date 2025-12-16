package ru.gigadesk.tool.calendar

import ru.gigadesk.tool.BadInputException
import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

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
        val calName = input.calendarName
        val escapedTitle = input.title.replace("\"", "\\\"")
        return bash.sh(CalendarAppleScriptCommands.deleteEventCommand(calName, escapedTitle))
    }
}
