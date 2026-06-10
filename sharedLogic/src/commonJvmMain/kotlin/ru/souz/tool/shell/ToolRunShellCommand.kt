package ru.souz.tool.shell

import kotlinx.coroutines.runBlocking
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.giga.toGiga
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolPermissionBroker
import ru.souz.tool.ToolPermissionResult
import ru.souz.tool.ToolSetup

class ToolRunShellCommand(
    private val sandboxResolver: ToolInvocationRuntimeSandboxResolver,
    private val permissionBroker: ToolPermissionBroker,
) : ToolSetup<ToolRunShellCommand.Input> {
    data class Input(
        @InputParamDescription("POSIX shell script to execute inside the Souz runtime sandbox. Android runs it with /system/bin/sh, not GNU Bash.")
        val script: String,
        @InputParamDescription("Working directory inside the Souz runtime sandbox. Defaults to ~, the sandbox home directory.")
        val workingDirectory: String? = DEFAULT_WORKING_DIRECTORY,
        @InputParamDescription("Environment variables to pass to the shell command. SOUZ_* keys are reserved.")
        val environment: Map<String, String> = emptyMap(),
        @InputParamDescription("Optional stdin passed to the shell command.")
        val stdin: String? = null,
        @InputParamDescription("Timeout in milliseconds. Defaults to 60000 and is capped at 300000.")
        val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    )

    override val name: String = NAME

    override val description: String = buildString {
        append("Run a POSIX shell command inside the Souz runtime sandbox. ")
        append("On Android this uses /system/bin/sh and can access only the app-private sandbox roots allowed by the runtime filesystem. ")
        append("Use this for Android shell inspection or small sandbox file operations when file tools are not enough. ")
        append("Do not assume GNU Bash features are available on Android.")
    }

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Show files in the Android sandbox home",
            params = mapOf(
                "script" to "ls -la .",
                "workingDirectory" to "~",
            ),
        )
    )

    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "exitCode" to ReturnProperty("number", "Process exit code, or -1 on timeout."),
            "timedOut" to ReturnProperty("boolean", "Whether the command timed out."),
            "stdout" to ReturnProperty("string", "Captured stdout, truncated when too large."),
            "stderr" to ReturnProperty("string", "Captured stderr, truncated when too large."),
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking {
        suspendInvoke(input, meta)
    }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        if (input.script.isBlank()) {
            throw BadInputException("script must not be blank.")
        }
        validateEnvironment(input.environment)
        val sandbox = sandboxResolver.resolve(meta)
        val workingDirectory = input.workingDirectory
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: DEFAULT_WORKING_DIRECTORY
        val resolvedWorkingDirectory = sandbox.fileSystem.resolveExistingDirectory(workingDirectory).path
        val timeoutMillis = input.timeoutMillis.coerceIn(1L, MAX_TIMEOUT_MILLIS)

        val permission = permissionBroker.requestPermission(
            description = "Run shell command",
            params = linkedMapOf(
                "workingDirectory" to resolvedWorkingDirectory,
                "script" to input.script.truncateForPermissionPrompt(),
                "timeoutMillis" to timeoutMillis.toString(),
            ),
        )
        if (permission is ToolPermissionResult.No) return permission.msg

        val result = sandbox.commandExecutor.execute(
            SandboxCommandRequest(
                runtime = SandboxCommandRuntime.BASH,
                script = input.script,
                workingDirectory = resolvedWorkingDirectory,
                environment = input.environment,
                stdin = input.stdin,
                timeoutMillis = timeoutMillis,
            )
        )
        return result.render()
    }

    private fun validateEnvironment(environment: Map<String, String>) {
        environment.forEach { (key, value) ->
            if (key.isBlank() || key.contains('=') || key.contains('\u0000')) {
                throw BadInputException("Invalid environment variable name: $key")
            }
            if (key.startsWith(RESERVED_ENVIRONMENT_PREFIX)) {
                throw BadInputException("Environment variable is reserved for shell execution: $key")
            }
            if (value.contains('\u0000')) {
                throw BadInputException("Environment variable contains a NUL character: $key")
            }
        }
    }

    private fun SandboxCommandResult.render(): String = buildString {
        appendLine("exitCode: $exitCode")
        appendLine("timedOut: $timedOut")
        appendLine("stdout:")
        appendLine(stdout.truncateToolOutput())
        appendLine("stderr:")
        append(stderr.truncateToolOutput())
    }

    private fun String.truncateToolOutput(): String {
        if (length <= MAX_OUTPUT_CHARS) return this
        val truncatedChars = length - MAX_OUTPUT_CHARS
        return take(MAX_OUTPUT_CHARS) + "\n...[truncated $truncatedChars chars]"
    }

    private fun String.truncateForPermissionPrompt(): String {
        if (length <= MAX_PERMISSION_SCRIPT_CHARS) return this
        val truncatedChars = length - MAX_PERMISSION_SCRIPT_CHARS
        return take(MAX_PERMISSION_SCRIPT_CHARS) + "\n...[truncated $truncatedChars chars]"
    }

    companion object {
        const val NAME = "RunShellCommand"
        private const val DEFAULT_WORKING_DIRECTORY = "~"
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 300_000L
        private const val MAX_OUTPUT_CHARS = 20_000
        private const val MAX_PERMISSION_SCRIPT_CHARS = 1_000
        private const val RESERVED_ENVIRONMENT_PREFIX = "SOUZ_"
    }
}

fun ToolRunShellCommand.toShellToolSetup(): LLMToolSetup = toGiga()
