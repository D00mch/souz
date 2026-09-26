package ru.souz.runtime.sandbox.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.startSandboxCommandOutputCapture
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal class LocalSandboxCommandExecutor(
    private val fileSystem: SandboxFileSystem,
) : SandboxCommandExecutor {
    override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult = withContext(Dispatchers.IO) {
        val workingDirectory = request.workingDirectory
            ?.let(fileSystem::resolveExistingDirectory)
            ?.path
        val command = request.toProcessCommand()
        val process = ProcessBuilder(command).apply {
            workingDirectory?.let { directory(File(it)) }
            redirectErrorStream(false)
            environment().putAll(request.environment)
        }.start()
        val output = process.startSandboxCommandOutputCapture("local-sandbox-command")

        // A blocked stdin write must not delay the interruptible process deadline.
        val input = async {
            runCatching {
                request.stdin?.let { input ->
                    process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer -> writer.write(input) }
                } ?: process.outputStream.close()
            }
        }
        val timedOut = try {
            runInterruptible {
                request.timeoutMillis?.let { timeout ->
                    !process.waitFor(timeout, TimeUnit.MILLISECONDS)
                } ?: run {
                    process.waitFor()
                    false
                }
            }
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
            }
            output.awaitDrainedOrClose()
        }

        if (!timedOut) input.await().getOrThrow()
        SandboxCommandResult(
            exitCode = if (timedOut) -1 else process.exitValue(),
            stdout = output.stdoutText(),
            stderr = output.stderrText(),
            timedOut = timedOut,
        )
    }

    private fun SandboxCommandRequest.toProcessCommand(): List<String> = when (runtime) {
        SandboxCommandRuntime.PROCESS -> {
            require(command.isNotEmpty()) { "command must not be empty for PROCESS runtime." }
            command
        }

        SandboxCommandRuntime.BASH -> scriptCommand("bash", "-lc", "script is required for BASH runtime.", inlineCommandName = "bash")
        SandboxCommandRuntime.PYTHON -> scriptCommand("python3", "-c", "script is required for PYTHON runtime.")
        SandboxCommandRuntime.NODE -> scriptCommand("node", "-e", "script is required for NODE runtime.")
    }

    private fun SandboxCommandRequest.scriptCommand(
        executable: String,
        inlineFlag: String,
        missingMessage: String,
        inlineCommandName: String? = null,
    ): List<String> {
        scriptPath?.let { return listOf(executable, fileSystem.resolveExistingFile(it).path) + args }
        val command = listOf(executable, inlineFlag, requireNotNull(script) { missingMessage })
        return if (inlineCommandName == null) command + args else command + inlineCommandName + args
    }
}
