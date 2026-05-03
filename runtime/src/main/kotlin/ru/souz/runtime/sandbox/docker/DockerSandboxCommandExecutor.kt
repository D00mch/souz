package ru.souz.runtime.sandbox.docker

import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.tool.BadInputException

internal class DockerSandboxCommandExecutor(
    private val containerHandle: DockerContainerHandle,
    private val fileSystem: SandboxFileSystem,
) : SandboxCommandExecutor {
    override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult {
        val workingDirectory = request.workingDirectory
            ?.let(fileSystem::resolveExistingDirectory)
            ?.path
        val command = request.toDockerExecCommand()
        val result = containerHandle.exec(
            command = command,
            workingDirectory = workingDirectory,
            environment = request.environment,
            stdin = request.stdin,
            timeoutMillis = request.timeoutMillis,
        )
        val timedOut = result.timedOut || result.exitCode == TIMEOUT_EXIT_CODE
        return SandboxCommandResult(
            exitCode = if (timedOut) -1 else result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = timedOut,
        )
    }

    private fun SandboxCommandRequest.toDockerExecCommand(): List<String> {
        val command = when (runtime) {
            SandboxCommandRuntime.PROCESS -> {
                if (command.isEmpty()) {
                    throw BadInputException("command must not be empty for PROCESS runtime.")
                }
                command
            }

            SandboxCommandRuntime.BASH -> listOf("bash", "-lc", requireNotNull(script) { "script is required for BASH runtime." })
            SandboxCommandRuntime.PYTHON -> listOf("python3", "-c", requireNotNull(script) { "script is required for PYTHON runtime." })
            SandboxCommandRuntime.NODE -> listOf("node", "-e", requireNotNull(script) { "script is required for NODE runtime." })
        }
        return timeoutMillis?.let { timeout ->
            listOf(
                "timeout",
                "--signal=TERM",
                "--kill-after=1s",
                "${timeout}ms",
            ) + command
        } ?: command
    }

    private companion object {
        const val TIMEOUT_EXIT_CODE = 124
    }
}
