package ru.souz.tool.notes

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ShellException
import ru.souz.tool.ToolRunBashCommand
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotesBackgroundAccessTest {
    private val bash = mockk<ToolRunBashCommand>()
    private val meta = ToolInvocationMeta.localDefault()
    private val payload = "Заметка \"quoted\" \\ path\nEOF\n${'$'}(exit 7) `exit 8`"

    @Test
    fun `data tools start Notes without run or activation and pass input literally`() {
        every { bash.apple(any(), any(), 30_000) } answers {
            val source = firstArg<String>()
            assertTrue(source.indexOf("launch") > source.indexOf("tell application \"Notes\""))
            assertFalse(Regex("(?m)^\\s*(activate|run|reopen|show|open)\\b").containsMatchIn(source))
            assertFalse(source.contains(payload))
            "fixture result"
        }
        val create = ToolCreateNote(bash)
        val list = ToolListNotes(bash)
        val search = ToolSearchNotes(bash)
        val delete = ToolDeleteNote(bash)
        verify { bash wasNot Called }
        assertEquals("Done", create.invoke(ToolCreateNote.Input(payload), meta))
        assertEquals("fixture result", list.invoke(ToolListNotes.Input, meta))
        assertEquals("fixture result", search.invoke(ToolSearchNotes.Input(payload), meta))
        assertEquals("Done", delete.invoke(ToolDeleteNote.Input(payload), meta))
        verify(exactly = 3) { bash.apple(any(), listOf(payload), 30_000) }
        verify(exactly = 1) { bash.apple(any(), emptyList(), 30_000) }
    }

    @Test
    fun `native denial reaches the caller instead of success or an empty list`() {
        every { bash.apple(any(), any(), any()) } throws ShellException("Not authorized to send Apple events to Notes (-1743)", 1)
        val calls = listOf<() -> String>(
            { ToolCreateNote(bash).invoke(ToolCreateNote.Input(payload), meta) },
            { ToolListNotes(bash).invoke(ToolListNotes.Input, meta) },
            { ToolSearchNotes(bash).invoke(ToolSearchNotes.Input(payload), meta) },
            { ToolDeleteNote(bash).invoke(ToolDeleteNote.Input(payload), meta) },
        )
        for (call in calls) assertTrue(assertFailsWith<ShellException> { call() }.message.orEmpty().contains("-1743"))
    }

    @Test
    fun `explicit open still activates and shows the note`() {
        every { bash.apple(any(), listOf(payload), 30_000) } answers {
            assertTrue(firstArg<String>().contains("activate"))
            assertTrue(firstArg<String>().contains("show firstNote"))
            "Done"
        }
        assertEquals("Done", ToolOpenNote(bash).invoke(ToolOpenNote.Input(payload), meta))
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `data scripts compile against Notes without executing or accessing accounts`() {
        every { bash.apple(any(), any(), any()) } answers {
            val output = Files.createTempFile("notes-background-test-", ".scpt").toFile()
            try {
                val process = ProcessBuilder("/usr/bin/osacompile", "-o", output.path, "-").redirectErrorStream(true).start()
                process.outputStream.bufferedWriter().use { it.write(firstArg<String>()) }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            } finally {
                output.delete()
            }
            "Done"
        }
        ToolCreateNote(bash).invoke(ToolCreateNote.Input(payload), meta)
        ToolListNotes(bash).invoke(ToolListNotes.Input, meta)
        ToolSearchNotes(bash).invoke(ToolSearchNotes.Input(payload), meta)
        ToolDeleteNote(bash).invoke(ToolDeleteNote.Input(payload), meta)
    }
}
