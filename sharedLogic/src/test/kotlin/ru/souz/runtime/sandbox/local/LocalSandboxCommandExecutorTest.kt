package ru.souz.runtime.sandbox.local

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.SANDBOX_COMMAND_OUTPUT_LIMIT_BYTES
import ru.souz.runtime.sandbox.SANDBOX_COMMAND_OUTPUT_TRUNCATION_PREFIX
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.tool.BadInputException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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

    @Test
    fun `executes script path with args`() = runTest {
        val home = createTempDirectory("sandbox-home-")
        val stateRoot = createTempDirectory("sandbox-state-")
        val scripts = home.resolve("scripts").createDirectories()
        val script = scripts.resolve("echo.sh").apply {
            writeText($$"printf '%s:%s:%s' \"$PWD\" \"$1\" \"$2\"")
        }
        val sandbox = createSandbox(home = home, stateRoot = stateRoot)

        val result = sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.BASH,
                scriptPath = script.toString(),
                args = listOf("first", "second"),
                workingDirectory = "~/scripts",
            ),
        )

        assertEquals(0, result.exitCode)
        assertEquals("${scripts.toRealPath()}:first:second", result.stdout)
    }

    @Test
    fun `truncates noisy stdout and stderr`() = runTest {
        val home = createTempDirectory("sandbox-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("sandbox-state-"))

        val result = sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.BASH,
                script = $$"""
                    i=0
                    while [ "$i" -lt 66000 ]; do printf o; i=$((i + 1)); done
                    i=0
                    while [ "$i" -lt 66000 ]; do printf e >&2; i=$((i + 1)); done
                """.trimIndent(),
                timeoutMillis = 30_000,
            )
        )

        assertEquals(0, result.exitCode)
        assertContains(result.stdout, SANDBOX_COMMAND_OUTPUT_TRUNCATION_PREFIX)
        assertContains(result.stderr, SANDBOX_COMMAND_OUTPUT_TRUNCATION_PREFIX)
        assertTrue(result.stdout.length < SANDBOX_COMMAND_OUTPUT_LIMIT_BYTES + 100)
        assertTrue(result.stderr.length < SANDBOX_COMMAND_OUTPUT_LIMIT_BYTES + 100)
    }

    @Test
    fun `does not hang when background child keeps stdout open`() = runBlocking {
        val home = createTempDirectory("sandbox-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("sandbox-state-"))

        val startedAt = System.nanoTime()
        val result = withTimeout(3_000.milliseconds) {
            sandbox.commandExecutor.execute(
                SandboxCommandRequest(
                    runtime = SandboxCommandRuntime.BASH,
                    script = "sleep 5 & disown; printf done",
                    timeoutMillis = 10_000,
                )
            )
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(0, result.exitCode)
        assertEquals("done", result.stdout)
        assertTrue(elapsedMillis < 3_000, "Command should return after stream-drain grace, elapsed=${elapsedMillis}ms")
    }

    @Test
    fun `returns timed out result when process exceeds timeout`() = runBlocking {
        val home = createTempDirectory("sandbox-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("sandbox-state-"))

        val result = withTimeout(3_000.milliseconds) {
            sandbox.commandExecutor.execute(
                SandboxCommandRequest(
                    runtime = SandboxCommandRuntime.BASH,
                    script = "sleep 5",
                    timeoutMillis = 100,
                )
            )
        }

        assertEquals(-1, result.exitCode)
        assertTrue(result.timedOut)
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
