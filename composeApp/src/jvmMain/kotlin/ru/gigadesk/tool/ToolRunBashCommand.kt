package ru.gigadesk.tool

import java.io.BufferedReader

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

    override fun invoke(input: Input): String = sh(input.command, *input.args.toTypedArray())

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

    data class Input(
        @InputParamDescription("The bash command to run, e.g., 'ls', 'echo Hello', './gradlew tasks'")
        val command: String,
        val args: List<String> = emptyList()
    )
}

class ShellException(msg: String, val exitCode: Int) : Exception(msg)

fun main() {
    val result = ToolRunBashCommand.invoke(ToolRunBashCommand.Input("open /System/Applications/Weather.app"))
    println(result)
}