package ru.souz.tool.mail

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.BadInputException
import ru.souz.tool.ToolRunBashCommand
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailToolsTest {
    private val bash = mockk<ToolRunBashCommand>()
    private val meta = ToolInvocationMeta.localDefault()
    private val commands = mutableListOf<String>()

    init {
        every { bash.sh(any(), *anyVararg()) } answers {
            commands += firstArg<String>()
            "Mail result"
        }
    }

    @Test
    fun `new mail still opens a draft by default with the existing field defaults`() {
        val result = ToolMailSendNewMessage(bash).invoke(ToolMailSendNewMessage.Input("ivan@example.com"), meta)

        assertEquals("Mail result", result)
        verify(exactly = 1) { bash.sh(any(), "ivan@example.com", "ivan@example.com", "No Subject", "") }
        assertTrue(commands.single().contains("visible:true"))
        assertTrue(commands.single().contains("activate"))
        assertFalse(commands.single().contains("send newMessage"))
    }

    @Test
    fun `confirmation alone still opens a reply draft without sending`() = runTest {
        val result = ToolMailReplyMessage(bash).suspendInvoke(
            ToolMailReplyMessage.Input(messageId = 42, confirmed = true), meta,
        )

        assertEquals("Mail result", result)
        verify(exactly = 1) { bash.sh(any(), "42", "") }
        assertTrue(commands.single().contains("set visible of newReply to true"))
        assertTrue(commands.single().contains("activate"))
        assertFalse(commands.single().contains("send newReply"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `model confirmation and same turn retries can only prepare emails`(confirmed: Boolean) = runTest {
        every { bash.sh(any(), *anyVararg()) } answers {
            commands += firstArg<String>()
            mailSnapshot
        }
        val approval = MailSendApproval(bash)
        val newMail = ToolMailSendNewMessage(bash, approval)
        val reply = ToolMailReplyMessage(bash, approval)
        val newInput = ToolMailSendNewMessage.Input("ivan@example.com", send = true, confirmed = confirmed)
        val replyInput = ToolMailReplyMessage.Input(42, send = true, confirmed = confirmed)
        repeat(2) {
            assertTrue(newMail.suspendInvoke(newInput, meta).contains("/mail_preview"))
            assertTrue(reply.suspendInvoke(replyInput, meta).contains("/mail_preview"))
        }
        assertEquals(4, commands.size)
        assertTrue(commands.none { it.contains("send newMessage") })
        assertTrue(commands.all { it.contains("close newMessage saving no") })
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `synchronous sends fail closed regardless of model confirmation`(confirmed: Boolean) {
        val approval = MailSendApproval(bash)
        assertFailsWith<BadInputException> {
            ToolMailSendNewMessage(bash, approval).invoke(
                ToolMailSendNewMessage.Input("ivan@example.com", send = true, confirmed = confirmed), meta,
            )
        }
        assertFailsWith<BadInputException> {
            ToolMailReplyMessage(bash, approval).invoke(ToolMailReplyMessage.Input(42, send = true, confirmed = confirmed), meta)
        }
        verify { bash wasNot Called }
    }

    @Test
    fun `unavailable host approval and invalid fields perform no shell work`() = runTest {
        for (address in listOf(" ", "ivan@example.com")) assertFailsWith<BadInputException> {
            ToolMailSendNewMessage(bash).suspendInvoke(
                ToolMailSendNewMessage.Input(address, send = true, confirmed = true), meta,
            )
        }
        for (id in listOf(0, 42)) assertFailsWith<BadInputException> {
            ToolMailReplyMessage(bash).suspendInvoke(ToolMailReplyMessage.Input(id, send = true, confirmed = true), meta)
        }
        verify { bash wasNot Called }
    }
}

internal val mailSnapshot = """{"sender":"owner@example.com","subject":"Re: Report","content":"Спасибо\\nQuoted original","to":["reply-to@example.com"],"cc":["copy@example.com"],"bcc":["hidden@example.com"]}"""
