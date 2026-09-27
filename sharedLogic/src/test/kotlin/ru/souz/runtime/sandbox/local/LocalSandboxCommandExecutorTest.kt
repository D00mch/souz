package ru.souz.runtime.sandbox.local

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import ru.souz.db.SettingsProvider
import ru.souz.runtime.sandbox.SANDBOX_COMMAND_OUTPUT_LIMIT_BYTES
import ru.souz.runtime.sandbox.SANDBOX_COMMAND_OUTPUT_TRUNCATION_PREFIX
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.tool.BadInputException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalSandboxCommandExecutorTest {
    @TempDir
    lateinit var tempRoot: Path

    @Test
    fun `executes command inside resolved sandbox working directory`() = runTest {
        val home = createTempDirectory("sandbox-home-")
        val workspace = home.resolve("workspace").createDirectories()
        val sandbox = createSandbox(home)

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
        val sandbox = createSandbox(home)

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
    fun `executes script path with args and stdin`() = runTest {
        val home = createTempDirectory("sandbox-home-")
        val scripts = home.resolve("scripts").createDirectories()
        val script = scripts.resolve("echo.sh").apply {
            writeText($$"printf '%s:%s:%s:' \"$PWD\" \"$1\" \"$2\"; cat")
        }
        val sandbox = createSandbox(home)
        val input = "Привет\n".repeat(4_000)

        val result = sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.BASH,
                scriptPath = script.toString(),
                args = listOf("first", "second"),
                workingDirectory = "~/scripts",
                stdin = input,
            ),
        )

        assertEquals(0, result.exitCode)
        assertEquals("${scripts.toRealPath()}:first:second:$input", result.stdout)
    }

    @Test
    fun `truncates noisy stdout and stderr`() = runTest {
        val home = createTempDirectory("sandbox-home-")
        val sandbox = createSandbox(home)

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
    fun `does not hang when background child retains stdin and stdout`() = runBlocking {
        val home = createTempDirectory("sandbox-home-")
        val sandbox = createSandbox(home)
        val pidFile = home.resolve("child-pid")
        val startedAt = System.nanoTime()
        try {
            val result = sandbox.commandExecutor.execute(SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                script = """
                    import pathlib, subprocess, sys
                    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(5)'],
                                             stdin=sys.stdin, stdout=sys.stdout, stderr=sys.stderr)
                    pathlib.Path('child-pid').write_text(str(child.pid))
                    print('done', end='', flush=True)
                """.trimIndent(),
                workingDirectory = home.toString(),
                timeoutMillis = 500,
                stdin = "x".repeat(90_000),
            ))

            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            assertEquals(0, result.exitCode)
            assertEquals("done", result.stdout)
            assertTrue(elapsedMillis < 3_000, "Inherited streams delayed completion: ${elapsedMillis}ms")
        } finally {
            if (Files.exists(pidFile)) {
                ProcessHandle.of(Files.readString(pidFile).toLong()).ifPresent { it.destroyForcibly() }
            }
        }
    }

    @Test
    fun `timeout and cancellation terminate the process and its children`() = runBlocking {
        for (cancel in listOf(false, true)) for (input in listOf(null, "x".repeat(90_000))) {
            val home = createTempDirectory("sandbox-home-")
            val sandbox = createSandbox(home)
            withTimeout(10_000) {
                val execution = async {
                    sandbox.commandExecutor.execute(SandboxCommandRequest(
                        runtime = SandboxCommandRuntime.BASH,
                        script = $$"sleep 30 & printf '%s %s' \"$$\" \"$!\" > pids.tmp; mv pids.tmp pids; wait",
                        workingDirectory = home.toString(), timeoutMillis = if (cancel) null else 1_000, stdin = input,
                    ))
                }
                // Cancel only after both PIDs are published; allow startup and draining in the test watchdog.
                val pidFile = home.resolve("pids")
                while (!Files.exists(pidFile)) delay(10)
                val pids = Files.readString(pidFile).split(' ').map(String::toLong)
                if (cancel) {
                    assertTrue(execution.isActive)
                    execution.cancelAndJoin()
                    assertTrue(execution.isCancelled)
                } else {
                    val result = execution.await()
                    assertEquals(-1, result.exitCode)
                    assertTrue(result.timedOut)
                }
                while (pids.any { ProcessHandle.of(it).map { process -> process.isAlive }.orElse(false) }) delay(10)
            }
        }
    }

    private fun createSandbox(home: Path) = LocalRuntimeSandbox(
        scope = SandboxScope(userId = "user-1"),
        settingsProvider = mockk<SettingsProvider> { every { forbiddenFolders } returns emptyList() },
        homePath = home,
        stateRoot = createTempDirectory("sandbox-state-"),
    )

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(tempRoot, prefix)
}
