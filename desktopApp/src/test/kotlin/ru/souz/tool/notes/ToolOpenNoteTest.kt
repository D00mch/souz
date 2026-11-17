package ru.souz.tool.notes

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.LLMResponse
import ru.souz.llms.giga.toGiga
import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.ShellException
import ru.souz.tool.ToolRunBashCommand
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolOpenNoteTest {
    private val bash = mockk<ToolRunBashCommand>()
    private val tool = ToolOpenNote(bash)
    private val meta = ToolInvocationMeta.localDefault()

    @Test
    fun `blank titles fail before starting native work`() {
        assertFailsWith<BadInputException> { tool.invoke(ToolOpenNote.Input(" \n"), meta) }
        verify { bash wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Done", "Note not found: missing"])
    fun `native status reaches the agent with a bounded call and a literal title`(status: String) = runBlocking {
        val title = "Иван \"quoted\" \\ path\nEOF\n${'$'}(exit 7) `exit 8`"
        every { bash.apple(any(), listOf(title), 30_000) } answers {
            assertFalse(firstArg<String>().contains(title))
            assertFalse(firstArg<String>().contains("display dialog"))
            status
        }
        val response = tool.toGiga().invoke(LLMResponse.FunctionCall("OpenNote", mapOf("noteName" to title)), meta)
        assertEquals(status, restJsonMapper.readTree(response.content).get("result").asText())
        verify(exactly = 1) { bash.apple(any(), listOf(title), 30_000) }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    @EnabledOnOs(OS.MAC)
    fun `native result branches finish without a dialog and preserve the first match`(matches: Int) {
        var source = ""
        every { bash.apple(any(), any(), any()) } answers { source = firstArg(); "Done" }
        tool.invoke(ToolOpenNote.Input("missing"), meta)

        // Compile the whole script against Notes' dictionary, without executing Notes.
        val compiled = Files.createTempFile("souz-note-test-", ".scpt").toFile()
        try {
            val compiler = ProcessBuilder("/usr/bin/osacompile", "-o", compiled.path, "-")
                .redirectErrorStream(true).start()
            compiler.outputStream.bufferedWriter().use { it.write(source) }
            assertTrue(compiler.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, compiler.exitValue(), compiler.inputStream.bufferedReader().readText())
        } finally {
            compiled.delete()
        }

        // Execute the production result branches with synthetic query results; no real Notes data.
        val branch = source.substringAfter("set foundNotes to (notes whose name is noteName)")
            .substringBeforeLast("end tell")
            .replace("show firstNote", "if firstNote is not \"first\" then error \"Wrong note selected\"")
        val notes = when (matches) { 0 -> "{}"; 1 -> "{\"first\"}"; else -> "{\"first\", \"second\"}" }
        val fixture = "set noteName to \"missing\"\nset foundNotes to $notes\n$branch"
        assertEquals(
            if (matches == 0) "Note not found: missing" else "Done",
            ToolRunBashCommand.apple(fixture, emptyList(), 5_000),
        )
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `native arguments remain data and native failures are not reported as success`() {
        val title = "Иван \"quoted\" \\ path\nEOF\n${'$'}(exit 7) `exit 8`"
        val echo = "on run argv\nreturn item 1 of argv\nend run"
        assertEquals(title, ToolRunBashCommand.apple(echo, listOf(title), 5_000))
        val error = assertFailsWith<ShellException> {
            ToolRunBashCommand.apple("error \"Notes unavailable\" number 42", emptyList(), 5_000)
        }
        assertTrue(error.message.orEmpty().contains("Notes unavailable"))
        assertTrue(error.exitCode != 0)
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `an unresponsive native process has a deadline and is terminated`() = runBlocking {
        val script = "delay 60\n-- ${UUID.randomUUID()}"
        val error = assertFailsWith<ShellException> {
            ToolRunBashCommand.apple(script, emptyList(), 100)
        }
        assertEquals(-1, error.exitCode)
        assertTrue(error.message.orEmpty().contains("timed out"))
        awaitProcessExit(script)
    }

    @Test
    @EnabledOnOs(OS.MAC)
    fun `waiting for Notes leaves the caller responsive and cancellation terminates the process`() = runBlocking {
        val script = "delay 60\n-- ${UUID.randomUUID()}"
        every { bash.apple(any(), any(), any()) } answers {
            ToolRunBashCommand.apple(script, emptyList(), 30_000)
        }
        val call = async { tool.suspendInvoke(ToolOpenNote.Input("missing"), meta) }
        try {
            withTimeout(5_000) {
                while (!hasProcess(script)) delay(10)
            }
            assertFalse(call.isCompleted)
        } finally {
            withTimeout(5_000) { call.cancelAndJoin() }
        }
        assertTrue(call.isCancelled)
        awaitProcessExit(script)
    }

    private suspend fun awaitProcessExit(script: String) = withTimeout(5_000) {
        while (hasProcess(script)) delay(10)
    }

    private fun hasProcess(script: String): Boolean = ProcessHandle.current().children().use { children ->
        children.anyMatch { process -> process.isAlive && process.info().arguments().orElse(emptyArray()).contains(script) }
    }
}
