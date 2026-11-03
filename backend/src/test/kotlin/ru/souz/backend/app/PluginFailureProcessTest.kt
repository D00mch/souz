package ru.souz.backend.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

class PluginFailureProcessTest {
    @Test
    fun `fatal plugin failure exits with failure even when shutdown stalls or throws`() {
        val classpath = Files.readString(Path.of(System.getProperty("plugin.host.classpath.file")))
        for (mode in listOf("drained", "throws", "stalled")) {
            val trace = Files.createTempFile("plugin-shutdown-", ".txt")
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, PluginExitProbe::class.java.name, mode, trace.toString(),
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Process watchdog did not terminate $mode")
                assertEquals(1, process.exitValue())
                assertEquals("http\nruntime\n", Files.readString(trace))
            } finally {
                process.destroyForcibly()
                Files.deleteIfExists(trace)
            }
        }
    }
}

/** Destructive recovery checks run only in a disposable JVM. */
object PluginExitProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val trace = Path.of(args[1])
        exitAfterPluginFailure(
            drainMillis = 500,
            stopHttpIntake = { Files.writeString(trace, "http\n", APPEND) },
            shutdownRuntime = {
                Files.writeString(trace, "runtime\n", APPEND)
                when (args[0]) {
                    "stalled" -> awaitCancellation()
                    "throws" -> error("cleanup failed")
                }
            },
        )
    }
}
