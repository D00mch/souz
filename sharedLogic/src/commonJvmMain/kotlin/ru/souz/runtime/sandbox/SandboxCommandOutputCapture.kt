package ru.souz.runtime.sandbox

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal const val SANDBOX_COMMAND_OUTPUT_LIMIT_BYTES = 64 * 1024
internal const val SANDBOX_COMMAND_OUTPUT_DRAIN_GRACE_MILLIS = 1_000L
internal const val SANDBOX_COMMAND_OUTPUT_TRUNCATION_PREFIX = "\n...[truncated "

internal class SandboxCommandOutputCapture(
    private val limitBytes: Int = SANDBOX_COMMAND_OUTPUT_LIMIT_BYTES,
) {
    private val stored = ByteArrayOutputStream(limitBytes.coerceAtMost(DEFAULT_BUFFER_SIZE))
    private var truncatedBytes = 0L

    init {
        require(limitBytes > 0) { "Command output limit must be positive." }
    }

    @Synchronized
    fun append(buffer: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val remainingBytes = limitBytes - stored.size()
        val storedBytes = remainingBytes.coerceAtMost(length).coerceAtLeast(0)
        if (storedBytes > 0) {
            stored.write(buffer, offset, storedBytes)
        }
        truncatedBytes += (length - storedBytes).toLong()
    }

    @Synchronized
    fun text(): String {
        val output = String(stored.toByteArray(), StandardCharsets.UTF_8)
        if (truncatedBytes == 0L) return output
        return "$output$SANDBOX_COMMAND_OUTPUT_TRUNCATION_PREFIX$truncatedBytes bytes]"
    }
}

internal class SandboxCommandProcessOutputCapture private constructor(
    private val process: Process,
    private val stdoutThread: Thread,
    private val stderrThread: Thread,
    private val stdoutCapture: SandboxCommandOutputCapture,
    private val stderrCapture: SandboxCommandOutputCapture,
) {
    fun awaitDrainedOrClose(timeoutMillis: Long = SANDBOX_COMMAND_OUTPUT_DRAIN_GRACE_MILLIS) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0L))
        stdoutThread.joinUntil(deadlineNanos)
        stderrThread.joinUntil(deadlineNanos)
        if (stdoutThread.isAlive || stderrThread.isAlive) {
            process.inputStream.closeQuietly()
            process.errorStream.closeQuietly()
        }
    }

    fun stdoutText(): String = stdoutCapture.text()

    fun stderrText(): String = stderrCapture.text()

    companion object {
        fun start(process: Process, threadNamePrefix: String): SandboxCommandProcessOutputCapture {
            val stdout = SandboxCommandOutputCapture()
            val stderr = SandboxCommandOutputCapture()
            val stdoutThread = process.inputStream.startDrainThread("$threadNamePrefix-stdout", stdout)
            val stderrThread = process.errorStream.startDrainThread("$threadNamePrefix-stderr", stderr)
            return SandboxCommandProcessOutputCapture(
                process = process,
                stdoutThread = stdoutThread,
                stderrThread = stderrThread,
                stdoutCapture = stdout,
                stderrCapture = stderr,
            )
        }
    }
}

internal fun Process.startSandboxCommandOutputCapture(
    threadNamePrefix: String,
): SandboxCommandProcessOutputCapture =
    SandboxCommandProcessOutputCapture.start(this, threadNamePrefix)

private fun InputStream.startDrainThread(
    name: String,
    capture: SandboxCommandOutputCapture,
): Thread {
    val stream = this
    return Thread {
        runCatching {
            stream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    capture.append(buffer, 0, read)
                }
            }
        }
    }.apply {
        this.name = name
        isDaemon = true
        start()
    }
}

private fun Thread.joinUntil(deadlineNanos: Long) {
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0L) return
    val remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
    try {
        join(remainingMillis)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

private fun InputStream.closeQuietly() {
    runCatching { close() }
}
