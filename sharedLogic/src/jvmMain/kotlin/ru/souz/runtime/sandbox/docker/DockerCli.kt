package ru.souz.runtime.sandbox.docker

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.runtime.sandbox.startSandboxCommandOutputCapture

internal class DockerCli(
    private val executable: String = "docker",
) {
    fun ensureAvailable() {
        val result = run(listOf("version", "--format", "{{.Client.Version}}"))
        check(result.exitCode == 0) {
            "Docker CLI is unavailable. stdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        }
    }

    fun ensureImageAvailable(imageName: String) {
        val result = run(listOf("image", "inspect", imageName))
        check(result.exitCode == 0) {
            "Docker sandbox image '$imageName' is unavailable. Build or pull it before using SOUZ_SANDBOX_MODE=docker.\n" +
                "stdout:\n${result.stdout}\nstderr:\n${result.stderr}"
        }
    }

    fun run(
        arguments: List<String>,
        stdin: String? = null,
        timeoutMillis: Long? = null,
    ): DockerCliResult = runProcess(
        arguments = arguments,
        stdin = stdin,
        timeoutMillis = timeoutMillis,
        outputThreadNamePrefix = "docker-cli",
    )

    suspend fun runAsync(
        arguments: List<String>,
        stdin: String? = null,
        timeoutMillis: Long? = null,
    ): DockerCliResult = withContext(Dispatchers.IO) {
        runProcess(
            arguments = arguments,
            stdin = stdin,
            timeoutMillis = timeoutMillis,
            outputThreadNamePrefix = "docker-cli-async",
        )
    }

    private fun runProcess(
        arguments: List<String>,
        stdin: String?,
        timeoutMillis: Long?,
        outputThreadNamePrefix: String,
    ): DockerCliResult {
        val process = ProcessBuilder(listOf(executable) + arguments)
            .redirectErrorStream(false)
            .start()
        val output = process.startSandboxCommandOutputCapture(outputThreadNamePrefix)

        stdin?.let { input ->
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(input)
            }
        } ?: process.outputStream.close()
        val timedOut = timeoutMillis?.let { timeout ->
            !process.waitFor(timeout, TimeUnit.MILLISECONDS)
        } ?: run {
            process.waitFor()
            false
        }
        if (timedOut) {
            process.destroyForcibly()
        }
        output.awaitDrainedOrClose()
        val exitCode = if (timedOut) -1 else process.exitValue()
        return DockerCliResult(
            exitCode = exitCode,
            stdout = output.stdoutText(),
            stderr = output.stderrText(),
            timedOut = timedOut,
        )
    }
}

internal data class DockerCliResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)
