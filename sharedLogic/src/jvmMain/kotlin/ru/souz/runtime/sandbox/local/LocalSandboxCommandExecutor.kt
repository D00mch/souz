package ru.souz.runtime.sandbox.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import ru.souz.runtime.sandbox.SandboxFileSystem
import ru.souz.runtime.sandbox.startSandboxCommandOutputCapture
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal class LocalSandboxCommandExecutor(
    private val fileSystem: SandboxFileSystem,
) : SandboxCommandExecutor {
    override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult = runInterruptible(Dispatchers.IO) {
        val workingDirectory = request.workingDirectory
            ?.let(fileSystem::resolveExistingDirectory)
            ?.path
        val command = request.toProcessCommand()
        // File-backed stdin cannot leave a writer blocked on a child that outlives its parent.
        val input = request.stdin?.let { Files.createTempFile("souz-command-", ".stdin") }
        try {
            if (input != null) Files.writeString(input, request.stdin)
            val process = ProcessBuilder(command).apply {
                workingDirectory?.let { directory(File(it)) }
                input?.let { redirectInput(it.toFile()) }
                redirectErrorStream(false)
                environment().putAll(request.environment)
            }.start()
            val output = process.startSandboxCommandOutputCapture("local-sandbox-command")
            process.outputStream.close()
            val timedOut = try {
                request.timeoutMillis?.let { !process.waitFor(it, TimeUnit.MILLISECONDS) } ?: run {
                    process.waitFor()
                    false
                }
            } finally {
                if (process.isAlive) {
                    process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    process.destroyForcibly()
                }
                output.awaitDrainedOrClose()
            }

            SandboxCommandResult(
                exitCode = if (timedOut) -1 else process.exitValue(),
                stdout = output.stdoutText(),
                stderr = output.stderrText(),
                timedOut = timedOut,
            )
        } finally {
            // Windows descendants can retain stdin after their parent exits; cleanup must not mask its result.
            input?.toFile()?.let { if (!it.delete()) it.deleteOnExit() }
        }
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
