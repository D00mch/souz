package ru.souz.tool.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.runtime.sandbox.SandboxPathInfo
import ru.souz.tool.ShellException
import ru.souz.tool.ToolRunBashCommand
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolOpenApplicationTest {
    @TempDir
    lateinit var directory: Path
    private val bash = mockk<ToolRunBashCommand>()
    private val files = mockk<FilesToolUtil>()
    private val tool = ToolOpen(bash, files)
    private val meta = ToolInvocationMeta.localDefault()

    @Test
    fun `bundle identifiers reach Launch Services without becoming folder searches`() {
        val bundle = "com.apple.Notes"
        every { files.applyDefaultEnvs(bundle) } returns directory.resolve(bundle).toString()
        every { bash.javaScript(any(), listOf(bundle), 10_000) } returns "true"
        every { bash.sh("open -b \"\$1\"", bundle) } returns ""

        assertEquals("Done", tool.invoke(ToolOpen.Input(bundle), meta))
        verify(exactly = 1) { bash.sh(any(), *anyVararg<String>()) }
        verify(exactly = 1) { bash.sh("open -b \"\$1\"", bundle) }
    }

    @Test
    fun `application bundles use Launch Services and pass their path literally`() {
        val app = Files.createDirectory(directory.resolve("Synthetic '\$literal.app")).toString()
        every { files.applyDefaultEnvs(app) } returns app
        every { bash.sh("open \"\$1\"", app) } returns ""

        assertEquals("Done", tool.invoke(ToolOpen.Input(app), meta))
        verify(exactly = 1) { bash.sh("open \"\$1\"", app) }
    }

    @Test
    fun `failed application launch remains an error rather than a folder search`() {
        val bundle = "com.apple.Notes"
        every { files.applyDefaultEnvs(bundle) } returns directory.resolve(bundle).toString()
        every { bash.javaScript(any(), listOf(bundle), 10_000) } returns "true"
        every { bash.sh("open -b \"\$1\"", bundle) } throws ShellException("Application not found", 1)

        assertEquals("Application not found", assertFailsWith<ShellException> {
            tool.invoke(ToolOpen.Input(bundle), meta)
        }.message)
        verify(exactly = 1) { bash.sh(any(), *anyVararg<String>()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Project v2.0", "com.example.project", "Archive.app", "release-2.0"])
    fun `unregistered dotted names reach folder lookup`(name: String) {
        val found = Files.createDirectories(directory.resolve("Documents").resolve(name)).toString()
        val folder = mockk<SandboxPathInfo>()
        every { folder.path } returns found
        every { files.applyDefaultEnvs(name) } returns directory.resolve(name).toString()
        every { bash.javaScript(any(), listOf(name), 10_000) } returns "false"
        every { bash.apple(match { it.contains("set folderName to \"$name\"") }) } returns found
        every { files.resolveSafeExistingDirectory(found, meta) } returns folder
        every { files.listDescendants(folder, Int.MAX_VALUE, false, meta) } returns emptyList()

        assertEquals("""{"path":"$found","files":[]}""", tool.invoke(ToolOpen.Input(name), meta))
        verify(exactly = 0) { bash.sh(any(), *anyVararg<String>()) }
        verify(exactly = 1) { bash.apple(any()) }
    }

    @Test
    fun `registration lookup errors reach the caller without opening or searching`() {
        val bundle = "com.apple.Notes"
        every { files.applyDefaultEnvs(bundle) } returns directory.resolve(bundle).toString()
        every { bash.javaScript(any(), listOf(bundle), 10_000) } throws ShellException("Lookup timed out", -1)

        assertEquals("Lookup timed out", assertFailsWith<ShellException> {
            tool.invoke(ToolOpen.Input(bundle), meta)
        }.message)
        verify(exactly = 0) { bash.sh(any(), *anyVararg<String>()) }
        verify(exactly = 0) { bash.apple(any()) }
    }
}
