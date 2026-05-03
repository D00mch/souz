package ru.souz.tool.files

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.runtime.sandbox.SandboxScope
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FilesToolUtilSandboxTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `resolves file tool paths through the sandbox without pre-expanding them in tools`() {
        val home = createTempDirectory("files-tool-home-")
        val stateRoot = createTempDirectory("files-tool-state-")
        val file = home.resolve("notes.txt").apply { writeText("Hello from sandbox") }
        val filesToolUtil = createFilesToolUtil(home = home, stateRoot = stateRoot)

        val resolved = filesToolUtil.resolveSafeExistingFile("~/notes.txt")
        val content = filesToolUtil.readUtf8TextFile(resolved)

        assertEquals(file.toRealPath().toString(), resolved.path)
        assertEquals("Hello from sandbox", content)
    }

    private fun createFilesToolUtil(
        home: Path,
        stateRoot: Path,
    ): FilesToolUtil {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        val sandbox = LocalRuntimeSandbox(
            scope = SandboxScope(userId = "user-1"),
            settingsProvider = settingsProvider,
            homePath = home,
            stateRoot = stateRoot,
        )
        return FilesToolUtil(sandbox)
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)
}
