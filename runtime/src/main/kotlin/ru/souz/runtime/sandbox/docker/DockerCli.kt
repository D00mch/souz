package ru.souz.runtime.sandbox.docker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
    ): DockerCliResult {
        val process = ProcessBuilder(listOf(executable) + arguments)
            .redirectErrorStream(false)
            .start()

        stdin?.let { input ->
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(input)
            }
        } ?: process.outputStream.close()

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdoutCopy = thread(start = true, name = "docker-cli-stdout") {
            process.inputStream.copyToOutput(stdout)
        }
        val stderrCopy = thread(start = true, name = "docker-cli-stderr") {
            process.errorStream.copyToOutput(stderr)
        }
        val timedOut = timeoutMillis?.let { timeout ->
            !process.waitFor(timeout, TimeUnit.MILLISECONDS)
        } ?: run {
            process.waitFor()
            false
        }
        if (timedOut) {
            process.destroyForcibly()
        }
        stdoutCopy.join()
        stderrCopy.join()
        val exitCode = if (timedOut) -1 else process.exitValue()
        return DockerCliResult(
            exitCode = exitCode,
            stdout = stdout.toString(StandardCharsets.UTF_8),
            stderr = stderr.toString(StandardCharsets.UTF_8),
            timedOut = timedOut,
        )
    }

    suspend fun runAsync(
        arguments: List<String>,
        stdin: String? = null,
        timeoutMillis: Long? = null,
    ): DockerCliResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val process = ProcessBuilder(listOf(executable) + arguments)
                .redirectErrorStream(false)
                .start()

            stdin?.let { input ->
                process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    writer.write(input)
                }
            } ?: process.outputStream.close()

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val stdoutCopy = async { process.inputStream.copyToOutput(stdout) }
            val stderrCopy = async { process.errorStream.copyToOutput(stderr) }

            val timedOut = timeoutMillis?.let { timeout ->
                !process.waitFor(timeout, TimeUnit.MILLISECONDS)
            } ?: run {
                process.waitFor()
                false
            }

            if (timedOut) {
                process.destroyForcibly()
            }

            stdoutCopy.await()
            stderrCopy.await()

            DockerCliResult(
                exitCode = if (timedOut) -1 else process.exitValue(),
                stdout = stdout.toString(StandardCharsets.UTF_8),
                stderr = stderr.toString(StandardCharsets.UTF_8),
                timedOut = timedOut,
            )
        }
    }

    private fun InputStream.copyToOutput(output: ByteArrayOutputStream) {
        use { source ->
            source.copyTo(output)
        }
    }
}

internal data class DockerCliResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)
