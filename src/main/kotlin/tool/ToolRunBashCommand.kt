package com.dumch.tool

import java.io.BufferedReader

object ToolRunBashCommand : ToolSetup<ToolRunBashCommand.Input> {
    override val name = "RunBashCommand"
    override val description = "Executes a bash command and returns its output"

    override fun invoke(input: Input): String {
        val process = ProcessBuilder("bash", "-c", input.command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val exitCode = process.waitFor()
        if (exitCode != 0) throw RuntimeException("Command failed with exit code $exitCode")
        return output.trim()
    }

    data class Input(
        @InputParamDescription("The bash command to run, e.g., 'ls', 'echo Hello', './gradlew tasks'")
        val command: String
    )
}