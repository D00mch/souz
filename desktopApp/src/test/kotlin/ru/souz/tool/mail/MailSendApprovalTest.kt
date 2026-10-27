package ru.souz.tool.mail

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.ShellException
import ru.souz.tool.ToolRunBashCommand
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailSendApprovalTest {
    private val bash = mockk<ToolRunBashCommand>()
    private var clock = 0L
    private val approval = MailSendApproval(bash) { clock }
    private val shown = mutableListOf<String>()
    private val args = listOf("42", "Спасибо")

    init {
        every { bash.sh(any(), *anyVararg()) } returns mailSnapshot
    }

    private suspend fun request(reply: Boolean = true, values: List<String> = args): String =
        approval.request(reply, values).substringAfter("/mail_preview ").substringBefore('.')

    private suspend fun command(text: String) = approval.handleOwnerCommand(text) { shown += it }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `owner must see all resolved fields before a single exact send`(reply: Boolean) = runTest {
        val values = if (reply) args else listOf("Ivan", "ivan@example.com", "Report", "Hello")
        val token = request(reply, values)
        command("/mail_send $token")
        verify(exactly = 1) { bash.sh(any(), *anyVararg()) }
        assertTrue(shown.last().contains("Review the email first"))

        command("/mail_preview $token")
        val preview = shown[shown.lastIndex - 1]
        for (field in listOf("reply-to@example.com", "copy@example.com", "hidden@example.com", "Re: Report", "Спасибо")) {
            assertTrue(preview.contains(field), field)
        }
        val sendScript = MailAppleScriptCommands.approvalCommand(reply, send = true)
        every { bash.sh(sendScript, *values.toTypedArray(), mailSnapshot) } returns "Email sent."
        command("/mail_send $token")
        command("/mail_send $token")
        verify(exactly = 1) { bash.sh(sendScript, *values.toTypedArray(), mailSnapshot) }
        assertTrue(shown.contains("Email sent."))
        assertTrue(shown.last().contains("No matching pending email"))
    }

    @Test
    fun `changed requests invalidate previous previews and cannot reuse approval`() = runTest {
        val old = request()
        command("/mail_preview $old")
        val new = request(values = listOf("99", "Changed reply"))
        command("/mail_send $old")
        command("/mail_send $new")
        verify(exactly = 2) { bash.sh(any(), *anyVararg()) }
        assertTrue(shown.last().contains("Review the email first"))
    }

    @Test
    fun `expiration cancellation and context clear discard approval`() = runTest {
        val expired = request()
        command("/mail_preview $expired")
        clock = 10 * 60 * 1_000L
        command("/mail_send $expired")
        val cancelled = request()
        command("/mail_preview $cancelled")
        command("/mail_cancel $cancelled")
        command("/mail_send $cancelled")
        val cleared = request()
        command("/mail_preview $cleared")
        approval.clear()
        command("/mail_send $cleared")
        verify(exactly = 3) { bash.sh(any(), *anyVararg()) }
        assertTrue(shown.last().contains("No matching pending email"))
    }

    @Test
    fun `failed delivery of a complete preview never enables sending`() = runTest {
        val content = "😀".repeat(4_000)
        val snapshot = restJsonMapper.readTree(mailSnapshot) as com.fasterxml.jackson.databind.node.ObjectNode
        snapshot.put("content", content)
        every { bash.sh(any(), *anyVararg()) } returns snapshot.toString()
        val token = request()
        val delivered = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            approval.handleOwnerCommand("/mail_preview $token") {
                delivered += it
                if (delivered.size == 2) error("Telegram unavailable")
            }
        }
        command("/mail_send $token")
        verify(exactly = 1) { bash.sh(any(), *anyVararg()) }
        assertTrue(shown.last().contains("Review the email first"))
        command("/mail_preview $token")
        assertTrue(shown.joinToString("").contains(content))
        assertTrue(shown.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }

    @Test
    fun `recipient changes and failed sends consume approval without retry`() = runTest {
        val token = request()
        command("/mail_preview $token")
        every { bash.sh(MailAppleScriptCommands.approvalCommand(true, send = true), *anyVararg()) } throws
            ShellException("Email changed since approval", 1)
        command("/mail_send $token")
        assertTrue(shown.last().contains("Email changed since approval"))
        command("/mail_send $token")
        verify(exactly = 2) { bash.sh(any(), *anyVararg()) }
    }

    @Test
    fun `incomplete or unresolved recipient previews fail closed`() = runTest {
        for (bad in listOf("{}", mailSnapshot.replace("[\"reply-to@example.com\"]", "[]"),
            mailSnapshot.replace("[\"reply-to@example.com\"]", "[null]"))) {
            every { bash.sh(any(), *anyVararg()) } returns bad
            assertFailsWith<BadInputException> { request() }
        }
        assertFalse(command("ordinary owner text"))
    }

    @Test
    fun `native script checks all resolved recipients and message fields before sending`() {
        for (reply in listOf(false, true)) {
            val script = MailAppleScriptCommands.approvalCommand(reply, send = true)
            for (kind in listOf("to", "cc", "bcc")) assertTrue(script.contains("address of every $kind recipient"))
            assertTrue(script.indexOf("isEqualToDictionary:expectedSnapshot") < script.indexOf("send newMessage"))
            assertTrue(script.contains("error \"Email changed since approval"))
            assertFalse(script.contains("activate"))
            assertFalse(script.contains("display dialog"))
            assertFalse(script.contains("visible:true"))
        }
    }
}
