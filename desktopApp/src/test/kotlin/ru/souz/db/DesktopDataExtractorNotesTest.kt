package ru.souz.db

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.Test
import ru.souz.tool.ShellException
import ru.souz.tool.ToolRunBashCommand
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopDataExtractorNotesTest {
    @Test
    fun `indexing skips stopped Notes and uses background launch if Notes was running`() {
        mockkObject(ToolRunBashCommand)
        try {
            val extractor = DesktopDataExtractor(mockk(), mockk())
            every { ToolRunBashCommand.sh("pgrep -x Notes") } throws ShellException("stopped", 1)
            assertTrue(extractor.notes().isEmpty())
            verify(exactly = 0) { ToolRunBashCommand.apple(any()) }

            every { ToolRunBashCommand.sh("pgrep -x Notes") } returns "123"
            every { ToolRunBashCommand.apple(any()) } answers {
                val source = firstArg<String>()
                assertTrue(source.indexOf("to launch") < source.indexOf("to set xs"))
                "Synthetic note"
            }
            assertEquals(listOf(StorredData("Synthetic note", StorredType.NOTES)), extractor.notes())
        } finally { unmockkObject(ToolRunBashCommand) }
    }
}
