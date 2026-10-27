package ru.souz.tool.mail

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import ru.souz.tool.ToolRunBashCommand
import java.nio.file.Files
import kotlin.test.assertEquals

class MailAppleScriptCommandsTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @EnabledOnOs(OS.MAC)
    fun `mail scripts compile against the native Mail dictionary without executing`(send: Boolean) {
        val directory = Files.createTempDirectory("mail-script-compile-test").toFile()
        try {
            for (command in listOf(
                MailAppleScriptCommands.sendNewMessageCommand(send), MailAppleScriptCommands.replyMessageCommand(send),
            )) {
                val source = command.substringAfter("<<'EOF'\n").substringBeforeLast("\nEOF")
                val process = ProcessBuilder("osacompile", "-o", directory.resolve("mail.scpt").path, "-")
                    .redirectErrorStream(true).start()
                process.outputStream.bufferedWriter().use { it.write(source) }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, process.waitFor(), output)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mail fields survive the shell as arguments rather than executable source`(send: Boolean) {
        val directory = Files.createTempDirectory("mail-command-test").toFile()
        try {
            // Capture osascript's arguments without launching Mail or sending any email.
            directory.resolve("osascript").apply {
                writeText("#!/bin/bash\nprintf '%s\\0' \"${'$'}@\"\ncat >/dev/null\n")
                check(setExecutable(true))
            }
            val payload = "Иван \"quoted\" \\ path\nEOF\n${'$'}(exit 7) `exit 8`\r\nEnd"
            val newMessageArgs = arrayOf(payload, "ivan@example.com", payload, payload)
            val replyArgs = arrayOf("42", payload)
            val prefix = "PATH=\"${'$'}1:${'$'}PATH\"\nexport PATH\nshift\n"

            assertEquals(
                (listOf("-") + newMessageArgs).joinToString("\u0000", postfix = "\u0000"),
                ToolRunBashCommand.sh(prefix + MailAppleScriptCommands.sendNewMessageCommand(send), directory.path, *newMessageArgs),
            )
            assertEquals(
                (listOf("-") + replyArgs).joinToString("\u0000", postfix = "\u0000"),
                ToolRunBashCommand.sh(prefix + MailAppleScriptCommands.replyMessageCommand(send), directory.path, *replyArgs),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
