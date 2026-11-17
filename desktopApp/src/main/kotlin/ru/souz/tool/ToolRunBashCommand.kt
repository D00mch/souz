package ru.souz.tool

import ru.souz.llms.ToolInvocationMeta

import java.io.BufferedReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit

object ToolRunBashCommand : ToolSetup<ToolRunBashCommand.Input> {
    override val name = "RunBashCommand"
    override val description = "Executes a bash command and returns its output"
    override val fewShotExamples = listOf(
        FewShotExample(
            request = "List files in the current directory",
            params = mapOf("command" to "ls")
        )
    )
    override val returnParameters = ReturnParameters(
        properties = mapOf(
            "result" to ReturnProperty("string", "Command output")
        )
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = sh(input.command, *input.args.toTypedArray())

    fun sh(str: String, vararg args: String): String {
        val process = ProcessBuilder("bash", "-c", str, "", *args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val exitCode = process.waitFor()
        if (exitCode != 0) throw ShellException(output, exitCode)
        return output.trim()
    }

    fun apple(script: String): String {
        val scriptInvocation = """
osascript <<EOF
$script
EOF
        """.trimIndent()
        val p = ProcessBuilder("bash","-lc", scriptInvocation)
            .redirectErrorStream(true)
            .start()
        val output = p.inputStream.bufferedReader().use(BufferedReader::readText)
        val exitCode = p.waitFor()
        if (exitCode != 0) throw ShellException(output, exitCode)
        return output.trim()
    }

    fun apple(script: String, args: List<String>, timeoutMillis: Long): String {
        require(timeoutMillis > 0)
        // A file keeps output draining without a pipe read blocking the process deadline.
        val outputFile = Files.createTempFile("souz-applescript-", ".txt").toFile()
        try {
            val process = ProcessBuilder(listOf("/usr/bin/osascript", "-e", script, "--") + args)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
            try {
                process.outputStream.close()
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    throw ShellException("AppleScript timed out after $timeoutMillis ms", -1)
                }
                val output = outputFile.readText().trim()
                if (process.exitValue() != 0) throw ShellException(output, process.exitValue())
                return output
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            outputFile.delete()
        }
    }

    data class Input(
        @InputParamDescription("The bash command to run, e.g., 'ls', 'echo Hello', './gradlew tasks'")
        val command: String,
        val args: List<String> = emptyList()
    )

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String = invoke(input, meta)
}

class ShellException(msg: String, val exitCode: Int) : Exception(msg)

fun main() {
    val result = ToolRunBashCommand.invoke(
        ToolRunBashCommand.Input("open /System/Applications/Weather.app"),
        ToolInvocationMeta.localDefault(),
    )
    println(result)
}
