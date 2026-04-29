package tool.files

import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.files.ToolFindFolders
import ru.souz.tool.files.FilesToolUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolFindFoldersTest {
    private val bash: ToolRunBashCommand = mockk()
    private val filesToolUtil: FilesToolUtil = mockk()
    private val tool = ToolFindFolders(filesToolUtil)

    @Test
    fun `filters forbidden paths and parses output`() {
        val safePath = "/Users/souz/Documents"
        val unsafePath = "/System/Library/Secret"

        every { bash.sh(any()) } returns "$safePath\n$unsafePath"

        every { filesToolUtil.isPathSafe(match { it.absolutePath == safePath }) } returns true
        every { filesToolUtil.isPathSafe(match { it.absolutePath == unsafePath }) } returns false

        val resultsJson = tool.invoke(ToolFindFolders.Input("Documents"))
        val results: List<String> = restJsonMapper.readValue(resultsJson)

        assertTrue(results.contains(safePath), "Expected safe path in results")
        assertFalse(results.contains(unsafePath), "Expected unsafe path to be filtered out")
    }

    @Test
    fun `handles quotes and special characters safely (Anti-Injection)`() {
        val nastyFolderName = "John's \"Folder\""

        every { bash.sh(any()) } returns ""

        tool.invoke(ToolFindFolders.Input(nastyFolderName))

        val capturedCommands = mutableListOf<String>()

        verify { bash.sh(capture(capturedCommands)) }

        val executedCommand = capturedCommands.first()

        assertTrue(executedCommand.startsWith("mdfind '"), "Command must start with mdfind '")

        assertTrue(executedCommand.contains("\\\"Folder\\\""), "Double quotes should be escaped for Spotlight")

        assertTrue(executedCommand.contains("John'\\''s"), "Single quotes should be escaped for Bash")
    }

    @Test
    fun `falls back to partial search if exact match is empty`() {
        val folderName = "Projects"
        val partialMatchPath = "/Users/souz/Old_Projects_Archive"

        every {
            bash.sh(match { it.contains("== \"$folderName\"c") })
        } returns ""

        every {
            bash.sh(match { it.contains("== \"*$folderName*\"c") })
        } returns partialMatchPath

        every { filesToolUtil.isPathSafe(any()) } returns true

        val resultsJson = tool.invoke(ToolFindFolders.Input(folderName))
        val results: List<String> = restJsonMapper.readValue(resultsJson)

        assertEquals(1, results.size)
        assertEquals(partialMatchPath, results.first())

        verify(exactly = 2) { bash.sh(any()) }
    }
}
