package ru.souz.backend.testutil

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** An argument file keeps the real classpath below the Windows process command-line limit. */
internal fun withPluginProbe(
    mainClass: Class<*>,
    arguments: List<String>,
    output: Path? = null,
    block: (Process) -> Unit,
) {
    val classpath = Files.readString(Path.of(requireNotNull(System.getProperty("plugin.host.classpath.file"))))
    val argFile = Files.createTempFile("plugin-jvm-", ".args")
    var process: Process? = null
    try {
        val quoted = "\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        Files.writeString(argFile, "-Xmx256m\n-cp\n$quoted\n")
        process = ProcessBuilder(listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "@${argFile.toAbsolutePath()}", mainClass.name,
        ) + arguments).redirectErrorStream(true).redirectOutput(
            output?.let { ProcessBuilder.Redirect.to(it.toFile()) } ?: ProcessBuilder.Redirect.DISCARD,
        ).start()
        block(process)
    } finally {
        process?.destroyForcibly()?.waitFor(5, TimeUnit.SECONDS)
        Files.deleteIfExists(argFile)
    }
}
