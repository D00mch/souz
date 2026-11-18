package ru.souz.tool.calendar

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.BadInputException
import ru.souz.tool.ShellException
import ru.souz.tool.ToolRunBashCommand
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarBackgroundAccessTest {
    @Test
    @EnabledOnOs(OS.MAC)
    fun `calendar data scripts launch before accessing data without UI commands`() {
        val commands = listOf(
            CalendarAppleScriptCommands.listTodayEventsCommand("Fixture"),
            CalendarAppleScriptCommands.listCalendarsCommand("Fixture"),
            CalendarAppleScriptCommands.createEventCommand("Fixture", "Synthetic", 2026, 11, 18, 10, 0, 30, "", "", 5),
            CalendarAppleScriptCommands.deleteEventCommand("Fixture", "Synthetic"),
        )
        for (command in commands) {
            val source = command.substringAfter("<<'EOF'\n").substringBeforeLast("\nEOF")
            assertTrue(Regex("tell application \"Calendar\"\\s+launch").containsMatchIn(source))
            assertFalse(Regex("(?m)^\\s*(activate|run|reopen|show|open)\\b").containsMatchIn(source))
            val output = Files.createTempFile("calendar-background-test-", ".scpt").toFile()
            try {
                val process = ProcessBuilder("/usr/bin/osacompile", "-o", output.path, "-").redirectErrorStream(true).start()
                process.outputStream.bufferedWriter().use { it.write(source) }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            } finally { output.delete() }
        }
    }

    @Test
    fun `event listing uses local day boundaries including daylight saving and preserves formatting`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val bash = mockk<ToolRunBashCommand>()
            val tool = ToolCalendarListEvents(bash)
            verify { bash wasNot Called }
            for ((date, hours) in listOf("2026-03-08" to 23L, "2026-11-01" to 25L)) {
                every { bash.javaScript(any(), any(), 35_000) } answers {
                    val args = secondArg<List<String>>()
                    assertEquals("Fixture", args[0])
                    val start = Instant.ofEpochSecond(args[1].toLong())
                    val end = Instant.ofEpochSecond(args[2].toLong())
                    assertEquals(hours, Duration.between(start, end).toHours())
                    assertEquals(date, start.atZone(ZoneId.systemDefault()).toLocalDate().toString())
                    """[{"title":"Timed","start":${start.epochSecond + 3600},"allDay":false},{"title":"All day","start":${start.epochSecond},"allDay":true}]"""
                }
                assertEquals("Events for $date in calendar 'Fixture':\n- [All Day]: All day\n- 01:00: Timed\n",
                    tool.invoke(ToolCalendarListEvents.Input("Fixture", date), ToolInvocationMeta.localDefault()))
            }
        } finally { TimeZone.setDefault(previous) }
    }

    @Test
    fun `invalid dates and native errors are not reported as no events`() {
        val bash = mockk<ToolRunBashCommand>()
        assertFailsWith<BadInputException> { CalendarEventKit.listEvents("Fixture", "invalid", bash) }
        verify { bash wasNot Called }
        every { bash.javaScript(any(), any(), any()) } throws ShellException("Calendar full access is required", 1)
        assertTrue(assertFailsWith<ShellException> { CalendarEventKit.listEvents("Fixture", "2026-11-18", bash) }
            .message.orEmpty().contains("full access"))
    }
}
