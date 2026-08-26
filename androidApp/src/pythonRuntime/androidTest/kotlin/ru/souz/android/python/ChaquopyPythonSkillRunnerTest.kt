package ru.souz.android.python

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.souz.android.sandbox.AndroidSandboxFileSystem
import ru.souz.android.settings.AndroidSettingsProvider
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandRuntime

@RunWith(AndroidJUnit4::class)
class ChaquopyPythonSkillRunnerTest {
    private lateinit var context: Context
    private lateinit var fileSystem: AndroidSandboxFileSystem
    private lateinit var runner: ChaquopyPythonSkillRunner
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileSystem = AndroidSandboxFileSystem(context, AndroidSettingsProvider(context))
        runner = ChaquopyPythonSkillRunner(context)
        testRoot = File(
            fileSystem.runtimePaths.stateRootPath,
            "python-runner-tests/${UUID.randomUUID()}",
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun inlinePythonCapturesStdoutAndStderr() = runBlocking {
        val result = runner.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                script = "import sys\nprint('hello')\nprint('warn', file=sys.stderr)",
                workingDirectory = testRoot.absolutePath,
                timeoutMillis = SUCCESS_TIMEOUT_MILLIS,
            ),
            fileSystem,
        )

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertEquals("hello\n", result.stdout)
        assertEquals("warn\n", result.stderr)
    }

    @Test
    fun scriptPathReceivesArgsStdinEnvironmentAndVendoredImports() = runBlocking {
        File(testRoot, "helper.py").writeText("VALUE = 'helper-ok'\n")
        val script = File(testRoot, "scripts/main.py").apply {
            parentFile?.mkdirs()
            writeText(
                """
                import helper
                import os
                import sys

                print(f"{helper.VALUE}|{sys.argv[1]}|{sys.stdin.read()}|{os.environ['SOUZ_SKILL_ID']}|{os.getcwd()}")
                """.trimIndent()
            )
        }

        val result = runner.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                scriptPath = script.absolutePath,
                args = listOf("alpha"),
                workingDirectory = testRoot.absolutePath,
                environment = mapOf(
                    "SOUZ_SKILL_ID" to "python-test-skill",
                    "SOUZ_SKILL_ROOT" to testRoot.absolutePath,
                ),
                stdin = "payload",
                timeoutMillis = SUCCESS_TIMEOUT_MILLIS,
            ),
            fileSystem,
        )

        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.stdout.startsWith("helper-ok|alpha|payload|python-test-skill|"))
        assertTrue(result.stdout.contains("python-runner-tests"))
    }

    @Test
    fun systemExitPreservesExitCode() = runBlocking {
        val result = runner.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                script = "import sys\nsys.exit(7)",
                workingDirectory = testRoot.absolutePath,
                timeoutMillis = SUCCESS_TIMEOUT_MILLIS,
            ),
            fileSystem,
        )

        assertEquals(7, result.exitCode)
        assertFalse(result.timedOut)
    }

    @Test
    fun uncaughtExceptionReturnsTracebackOnStderr() = runBlocking {
        val result = runner.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                script = "raise RuntimeError('boom')",
                workingDirectory = testRoot.absolutePath,
                timeoutMillis = SUCCESS_TIMEOUT_MILLIS,
            ),
            fileSystem,
        )

        assertEquals(1, result.exitCode)
        assertFalse(result.timedOut)
        assertTrue(result.stderr.contains("RuntimeError: boom"))
    }

    @Test
    fun timeoutReturnsTimedOutResult() = runBlocking {
        val result = runner.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                script = "while True:\n    pass",
                workingDirectory = testRoot.absolutePath,
                timeoutMillis = 100,
            ),
            fileSystem,
        )

        assertEquals(-1, result.exitCode)
        assertTrue(result.timedOut)
        assertTrue(result.stderr.contains("timed out"))
    }

    @Test
    fun blockingSleepTimeoutDoesNotBlockNextCommand() = runBlocking {
        val result = runner.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                script = "import time\ntime.sleep(30)",
                workingDirectory = testRoot.absolutePath,
                timeoutMillis = 3_000,
            ),
            fileSystem,
        )

        assertEquals(-1, result.exitCode)
        assertTrue(result.timedOut)
        assertTrue(result.stderr.contains("timed out"))

        val next = runner.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                script = "print('after-timeout')",
                workingDirectory = testRoot.absolutePath,
                timeoutMillis = SUCCESS_TIMEOUT_MILLIS,
            ),
            fileSystem,
        )

        assertEquals(0, next.exitCode)
        assertFalse(next.timedOut)
        assertEquals("after-timeout\n", next.stdout)
    }

    @Test
    fun sameModuleNameIsIsolatedAcrossCommands() = runBlocking {
        val firstRoot = File(testRoot, "first").apply { mkdirs() }
        val secondRoot = File(testRoot, "second").apply { mkdirs() }
        File(firstRoot, "helper.py").writeText("VALUE = 'first-helper'\n")
        File(secondRoot, "helper.py").writeText("VALUE = 'second-helper'\n")
        val firstScript = File(firstRoot, "main.py").apply {
            writeText("import helper\nprint(helper.VALUE)")
        }
        val secondScript = File(secondRoot, "main.py").apply {
            writeText("import helper\nprint(helper.VALUE)")
        }

        val first = runner.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                scriptPath = firstScript.absolutePath,
                workingDirectory = firstRoot.absolutePath,
                environment = mapOf("SOUZ_SKILL_ROOT" to firstRoot.absolutePath),
                timeoutMillis = SUCCESS_TIMEOUT_MILLIS,
            ),
            fileSystem,
        )
        val second = runner.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                scriptPath = secondScript.absolutePath,
                workingDirectory = secondRoot.absolutePath,
                environment = mapOf("SOUZ_SKILL_ROOT" to secondRoot.absolutePath),
                timeoutMillis = SUCCESS_TIMEOUT_MILLIS,
            ),
            fileSystem,
        )

        assertEquals(0, first.exitCode)
        assertEquals("first-helper\n", first.stdout)
        assertEquals(0, second.exitCode)
        assertEquals("second-helper\n", second.stdout)
    }

    @Test
    fun largeStdoutAndStderrAreTruncated() = runBlocking {
        val result = runner.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.PYTHON,
                script = """
                    import sys
                    print("o" * (70 * 1024))
                    print("e" * (70 * 1024), file=sys.stderr)
                """.trimIndent(),
                workingDirectory = testRoot.absolutePath,
                timeoutMillis = SUCCESS_TIMEOUT_MILLIS,
            ),
            fileSystem,
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("...[truncated "))
        assertTrue(result.stderr.contains("...[truncated "))
        assertTrue(result.stdout.length < 67 * 1024)
        assertTrue(result.stderr.length < 67 * 1024)
    }

    private companion object {
        const val SUCCESS_TIMEOUT_MILLIS = 15_000L
    }
}
