package ru.souz.runtime.sandbox.local

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.tool.BadInputException
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalSandboxCommandExecutorTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `executes command inside resolved sandbox working directory`() = runTest {
        val home = createTempDirectory("sandbox-home-")
        val stateRoot = createTempDirectory("sandbox-state-")
        val workspace = home.resolve("workspace").createDirectories()
        val sandbox = createSandbox(home = home, stateRoot = stateRoot)

        val result = sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.BASH,
                script = "pwd",
                workingDirectory = "~/workspace",
            ),
        )

        assertEquals(0, result.exitCode)
        assertEquals(workspace.toRealPath().toString(), result.stdout.trim())
    }

    @Test
    fun `rejects working directory outside sandbox`() = runTest {
        val home = createTempDirectory("sandbox-home-")
        val outside = createTempDirectory("sandbox-outside-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("sandbox-state-"))

        val error = assertFailsWith<BadInputException> {
            sandbox.commandExecutor.execute(
                SandboxCommandRequest(
                    runtime = SandboxCommandRuntime.BASH,
                    script = "pwd",
                    workingDirectory = outside.toString(),
                ),
            )
        }

        assertContains(error.message.orEmpty(), "Forbidden directory")
    }

    private fun createSandbox(
        home: Path,
        stateRoot: Path,
    ): LocalRuntimeSandbox {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.forbiddenFolders } returns emptyList()
        return LocalRuntimeSandbox(
            scope = SandboxScope(userId = "user-1"),
            settingsProvider = settingsProvider,
            homePath = home,
            stateRoot = stateRoot,
        )
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)
}
