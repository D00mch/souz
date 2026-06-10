package ru.souz.tool.shell

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.tool.BadInputException
import ru.souz.tool.ImmediateToolPermissionBroker
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.ToolPermissionRequest
import ru.souz.tool.ToolPermissionResult
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRunShellCommandTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `executes shell script with stdin and environment`() = runTest {
        val home = createTempDirectory("shell-tool-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("shell-tool-state-"))
        val broker = RecordingPermissionBroker()
        val tool = createTool(sandbox, broker)

        val result = tool.suspendInvoke(
            ToolRunShellCommand.Input(
                script = "printf '%s:%s' \"${'$'}(cat)\" \"${'$'}EXTRA\"",
                environment = mapOf("EXTRA" to "env-ok"),
                stdin = "stdin-ok",
                timeoutMillis = 1_000,
            ),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertContains(result, "exitCode: 0")
        assertContains(result, "stdin-ok:env-ok")
        assertEquals("Run shell command", broker.recorded.single().description)
    }

    @Test
    fun `defaults working directory to sandbox home`() = runTest {
        val home = createTempDirectory("shell-tool-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("shell-tool-state-"))
        val tool = createTool(sandbox, RecordingPermissionBroker())

        val result = tool.suspendInvoke(
            ToolRunShellCommand.Input(
                script = "printf '%s' \"${'$'}PWD\"",
                timeoutMillis = 1_000,
            ),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertContains(result, "exitCode: 0")
        assertContains(result, home.toRealPath().toString())
    }

    @Test
    fun `denied permission does not execute script`() = runTest {
        val home = createTempDirectory("shell-tool-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("shell-tool-state-"))
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.safeModeEnabled } returns true
        val broker = ImmediateToolPermissionBroker(settingsProvider)
        val tool = createTool(sandbox, broker)
        val touched = home.resolve("should-not-exist.txt")

        val result = async {
            tool.suspendInvoke(
                ToolRunShellCommand.Input(
                    script = "printf bad > should-not-exist.txt",
                    timeoutMillis = 1_000,
                ),
                ToolInvocationMeta(userId = "user-1"),
            )
        }
        val request = broker.requests.first()
        assertEquals("Run shell command", request.description)
        broker.resolve(request.id, approved = false)

        assertEquals("User disapproved", result.await())
        assertFalse(touched.exists())
    }

    @Test
    fun `immediate broker prompts when safe mode is on`() = runTest {
        val home = createTempDirectory("shell-tool-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("shell-tool-state-"))
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.safeModeEnabled } returns true
        val broker = ImmediateToolPermissionBroker(settingsProvider)
        val tool = createTool(sandbox, broker)

        val result = async {
            tool.suspendInvoke(
                ToolRunShellCommand.Input(
                    script = "printf safe-mode-approval",
                    timeoutMillis = 1_000,
                ),
                ToolInvocationMeta(userId = "user-1"),
            )
        }
        val request = broker.requests.first()
        assertEquals("Run shell command", request.description)
        assertContains(request.params.getValue("script"), "printf safe-mode-approval")

        broker.resolve(request.id, approved = true)

        assertContains(result.await(), "safe-mode-approval")
    }

    @Test
    fun `immediate broker runs without prompt when safe mode is off`() = runTest {
        val home = createTempDirectory("shell-tool-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("shell-tool-state-"))
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.safeModeEnabled } returns false
        val broker = ImmediateToolPermissionBroker(settingsProvider)
        val tool = createTool(sandbox, broker)

        val result = tool.suspendInvoke(
            ToolRunShellCommand.Input(
                script = "printf no-prompt",
                timeoutMillis = 1_000,
            ),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertContains(result, "no-prompt")
    }

    @Test
    fun `returns timed out result when script exceeds timeout`() = runTest {
        val home = createTempDirectory("shell-tool-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("shell-tool-state-"))
        val tool = createTool(sandbox, RecordingPermissionBroker())

        val result = tool.suspendInvoke(
            ToolRunShellCommand.Input(
                script = "sleep 5",
                timeoutMillis = 100,
            ),
            ToolInvocationMeta(userId = "user-1"),
        )

        assertContains(result, "exitCode: -1")
        assertContains(result, "timedOut: true")
    }

    @Test
    fun `rejects blank script`() = runTest {
        val home = createTempDirectory("shell-tool-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("shell-tool-state-"))
        val tool = createTool(sandbox, RecordingPermissionBroker())

        assertFailsWith<BadInputException> {
            tool.suspendInvoke(
                ToolRunShellCommand.Input(script = "   "),
                ToolInvocationMeta(userId = "user-1"),
            )
        }
    }

    @Test
    fun `rejects reserved environment variables`() = runTest {
        val home = createTempDirectory("shell-tool-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("shell-tool-state-"))
        val tool = createTool(sandbox, RecordingPermissionBroker())

        val error = assertFailsWith<BadInputException> {
            tool.suspendInvoke(
                ToolRunShellCommand.Input(
                    script = "printf ok",
                    environment = mapOf("SOUZ_INTERNAL" to "bad"),
                ),
                ToolInvocationMeta(userId = "user-1"),
            )
        }

        assertContains(error.message.orEmpty(), "SOUZ_INTERNAL")
    }

    @Test
    fun `rejects invalid environment variable names`() = runTest {
        val home = createTempDirectory("shell-tool-home-")
        val sandbox = createSandbox(home = home, stateRoot = createTempDirectory("shell-tool-state-"))
        val tool = createTool(sandbox, RecordingPermissionBroker())

        val error = assertFailsWith<BadInputException> {
            tool.suspendInvoke(
                ToolRunShellCommand.Input(
                    script = "printf ok",
                    environment = mapOf("BAD=NAME" to "bad"),
                ),
                ToolInvocationMeta(userId = "user-1"),
            )
        }

        assertContains(error.message.orEmpty(), "BAD=NAME")
    }

    private fun createTool(
        sandbox: LocalRuntimeSandbox,
        broker: ToolPermissionBroker,
    ): ToolRunShellCommand = ToolRunShellCommand(
        sandboxResolver = ToolInvocationRuntimeSandboxResolver.fixed(sandbox),
        permissionBroker = broker,
    )

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

    private class RecordingPermissionBroker(
        private val result: ToolPermissionResult = ToolPermissionResult.Ok,
    ) : ToolPermissionBroker {
        data class RecordedRequest(
            val description: String,
            val params: Map<String, String>,
        )

        val recorded = mutableListOf<RecordedRequest>()

        override val requests: Flow<ToolPermissionRequest> = emptyFlow()

        override suspend fun requestPermission(
            description: String,
            params: Map<String, String>,
        ): ToolPermissionResult {
            recorded += RecordedRequest(description, params)
            return result
        }

        override suspend fun resolve(requestId: Long, approved: Boolean) = Unit
    }
}
