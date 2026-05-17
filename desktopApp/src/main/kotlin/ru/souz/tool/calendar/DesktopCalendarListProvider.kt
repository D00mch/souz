package ru.souz.tool.calendar

import ru.souz.tool.ToolRunBashCommand
import ru.souz.ui.host.CalendarListProvider

class DesktopCalendarListProvider(
    private val bash: ToolRunBashCommand,
) : CalendarListProvider {
    override fun listCalendars(): List<String> {
        val result = bash.sh(CalendarAppleScriptCommands.listCalendarsCommand(""))
        return result
            .lines()
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }
}
