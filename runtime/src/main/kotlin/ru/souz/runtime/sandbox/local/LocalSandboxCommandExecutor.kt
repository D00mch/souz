package ru.souz.runtime.sandbox.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxCommandRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal class LocalSandboxCommandExecutor : SandboxCommandExecutor {
    override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult = withContext(Dispatchers.IO) {
        val command = request.toProcessCommand()
        val process = ProcessBuilder(command).apply {
            request.workingDirectory?.let { directory(File(it)) }
            redirectErrorStream(false)
            environment().putAll(request.environment)
        }.start()

        request.stdin?.let { input ->
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(input)
            }
        } ?: process.outputStream.close()

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdoutThread = streamAsync(process.inputStream, stdout)
        val stderrThread = streamAsync(process.errorStream, stderr)

        val timedOut = request.timeoutMillis?.let { timeout ->
            !process.waitFor(timeout, TimeUnit.MILLISECONDS)
        } ?: run {
            process.waitFor()
            false
        }

        if (timedOut) {
            process.destroyForcibly()
        }

        stdoutThread.join()
        stderrThread.join()

        SandboxCommandResult(
            exitCode = if (timedOut) -1 else process.exitValue(),
            stdout = stdout.toString(StandardCharsets.UTF_8),
            stderr = stderr.toString(StandardCharsets.UTF_8),
            timedOut = timedOut,
        )
    }

    private fun SandboxCommandRequest.toProcessCommand(): List<String> = when (runtime) {
        SandboxCommandRuntime.PROCESS -> {
            require(command.isNotEmpty()) { "command must not be empty for PROCESS runtime." }
            command
        }

        SandboxCommandRuntime.BASH -> listOf("bash", "-lc", requireNotNull(script) { "script is required for BASH runtime." })
        SandboxCommandRuntime.PYTHON -> listOf("python3", "-c", requireNotNull(script) { "script is required for PYTHON runtime." })
        SandboxCommandRuntime.NODE -> listOf("node", "-e", requireNotNull(script) { "script is required for NODE runtime." })
    }

    private fun streamAsync(input: InputStream, output: ByteArrayOutputStream): Thread =
        Thread {
            input.use { source ->
                source.copyTo(output)
            }
        }.also(Thread::start)
}