package ru.souz.tool

import java.io.BufferedReader

fun ToolRunBashCommand.apple(script: String): String {
    val scriptInvocation = """
osascript <<EOF
$script
EOF
    """.trimIndent()
    val process = ProcessBuilder("bash", "-lc", scriptInvocation)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
    val exitCode = process.waitFor()
    if (exitCode != 0) throw ShellException(output, exitCode)
    return output.trim()
}
