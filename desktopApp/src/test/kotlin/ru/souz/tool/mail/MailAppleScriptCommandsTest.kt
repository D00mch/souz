package ru.souz.tool.mail

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import ru.souz.tool.ToolRunBashCommand
import ru.souz.llms.restJsonMapper
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailAppleScriptCommandsTest {
    @ParameterizedTest
    @ValueSource(strings = ["unchanged", "to", "cc", "bcc", "sender", "subject", "content"])
    @EnabledOnOs(OS.MAC)
    fun `native approval guard rejects changed delivery fields without executing Mail`(changed: String) {
        val approved = restJsonMapper.readTree(mailSnapshot)
        val actual = restJsonMapper.readTree(mailSnapshot) as com.fasterxml.jackson.databind.node.ObjectNode
        when (changed) {
            "unchanged" -> Unit
            "to", "cc", "bcc" -> actual.putArray(changed).add("unexpected@example.com")
            else -> actual.put(changed, "Changed after preview")
        }
        // Execute the production comparison guard against fixture dictionaries. Mail is never launched.
        val production = MailAppleScriptCommands.approvalCommand(reply = true, send = true)
        val guard = production.substringAfter("set expectedText to").substringBefore("if (send newMessage)")
        val script = """
            use framework "Foundation"
            on run argv
                set actualText to current application's NSString's stringWithString:(item 1 of argv)
                set actualData to actualText's dataUsingEncoding:(current application's NSUTF8StringEncoding)
                set actualSnapshot to current application's NSJSONSerialization's JSONObjectWithData:actualData options:0 |error|:(missing value)
                set expectedText to $guard
                return "approved"
            end run
        """.trimIndent()
        val process = ProcessBuilder("osascript", "-", actual.toString(), approved.toString())
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (changed == "unchanged") {
            assertEquals(0, exit, output)
            assertEquals("approved", output.trim())
        } else {
            assertTrue(exit != 0, output)
            assertTrue(output.contains("Email changed since approval"), output)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @EnabledOnOs(OS.MAC)
    fun `mail scripts compile against the native Mail dictionary without executing`(send: Boolean) {
        val directory = Files.createTempDirectory("mail-script-compile-test").toFile()
        try {
            for (command in listOf(
                MailAppleScriptCommands.sendNewMessageCommand(), MailAppleScriptCommands.replyMessageCommand(),
                MailAppleScriptCommands.approvalCommand(reply = false, send = send),
                MailAppleScriptCommands.approvalCommand(reply = true, send = send),
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
            val approvalArgs = if (send) arrayOf(mailSnapshot) else emptyArray()
            val newMessageArgs = arrayOf(payload, "ivan@example.com", payload, payload) + approvalArgs
            val replyArgs = arrayOf("42", payload) + approvalArgs
            val prefix = "PATH=\"${'$'}1:${'$'}PATH\"\nexport PATH\nshift\n"

            assertEquals(
                (listOf("-") + newMessageArgs).joinToString("\u0000", postfix = "\u0000"),
                ToolRunBashCommand.sh(prefix + MailAppleScriptCommands.approvalCommand(reply = false, send = send), directory.path, *newMessageArgs),
            )
            assertEquals(
                (listOf("-") + replyArgs).joinToString("\u0000", postfix = "\u0000"),
                ToolRunBashCommand.sh(prefix + MailAppleScriptCommands.approvalCommand(reply = true, send = send), directory.path, *replyArgs),
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
