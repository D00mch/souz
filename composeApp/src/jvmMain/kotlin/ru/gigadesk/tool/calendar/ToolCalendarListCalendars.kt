package ru.gigadesk.tool.calendar

import ru.gigadesk.tool.FewShotExample
import ru.gigadesk.tool.InputParamDescription
import ru.gigadesk.tool.ReturnParameters
import ru.gigadesk.tool.ReturnProperty
import ru.gigadesk.tool.ToolRunBashCommand
import ru.gigadesk.tool.ToolSetup

class ToolCalendarListCalendars(private val bash: ToolRunBashCommand) : ToolSetup<ToolCalendarListCalendars.Input> {

    data class Input(
        @InputParamDescription("Optional: Part of the name to search for (e.g. 'Work'). Leave empty to list all.")
        val nameFilter: String? = null
    )

    override val name: String = "CalendarListCalendars"
    override val description: String = "Returns a list of available calendars. Can filter by name."

    override val fewShotExamples = listOf(
        FewShotExample(
            request = "Какие у меня есть календари?",
            params = mapOf() // nameFilter будет null
        ),
        FewShotExample(
            request = "Есть ли у меня календарь 'Рабочий'?",
            params = mapOf(
                "nameFilter" to "Рабочий"
            )
        )
    )

    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "List of calendar names")
        )
    )

    override fun invoke(input: Input): String {
        return bash.sh(CalendarAppleScriptCommands.listCalendarsCommand(input.nameFilter))
    }
}

fun main() {
    val tool = ToolCalendarListCalendars(ToolRunBashCommand)
    println(tool.invoke(ToolCalendarListCalendars.Input()))
}