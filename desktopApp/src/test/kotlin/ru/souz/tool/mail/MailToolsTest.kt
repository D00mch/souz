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
import ru.souz.tool.ShellException
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
    fun `unconfirmed new email never executes Mail through either entry point`(suspending: Boolean) = runTest {
        val tool = ToolMailSendNewMessage(bash)
        val input = ToolMailSendNewMessage.Input("ivan@example.com", content = "Hello", send = true)

        val error = assertFailsWith<BadInputException> {
            if (suspending) tool.suspendInvoke(input, meta) else tool.invoke(input, meta)
        }

        assertTrue(error.message.orEmpty().contains("only after explicit approval"))
        verify { bash wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unconfirmed reply never executes Mail through either entry point`(suspending: Boolean) = runTest {
        val tool = ToolMailReplyMessage(bash)
        val input = ToolMailReplyMessage.Input(messageId = 42, content = "Thanks", send = true)

        val error = assertFailsWith<BadInputException> {
            if (suspending) tool.suspendInvoke(input, meta) else tool.invoke(input, meta)
        }

        assertTrue(error.message.orEmpty().contains("only after explicit approval"))
        verify { bash wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `confirmed new email sends once without opening Mail through either entry point`(suspending: Boolean) = runTest {
        val tool = ToolMailSendNewMessage(bash)
        val input = ToolMailSendNewMessage.Input(
            recipientAddress = "ivan@example.com", recipientName = "Иван", subject = "Отчёт",
            content = "Готово", send = true, confirmed = true,
        )

        val result = if (suspending) tool.suspendInvoke(input, meta) else tool.invoke(input, meta)

        assertEquals("Mail result", result)
        verify(exactly = 1) { bash.sh(any(), "Иван", "ivan@example.com", "Отчёт", "Готово") }
        assertTrue(commands.single().contains("send newMessage"))
        assertTrue(commands.single().contains("visible:false"))
        assertFalse(commands.single().contains("activate"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `confirmed reply sends once without opening Mail through either entry point`(suspending: Boolean) = runTest {
        val tool = ToolMailReplyMessage(bash)
        val input = ToolMailReplyMessage.Input(messageId = 42, content = "Спасибо", send = true, confirmed = true)

        val result = if (suspending) tool.suspendInvoke(input, meta) else tool.invoke(input, meta)

        assertEquals("Mail result", result)
        verify(exactly = 1) { bash.sh(any(), "42", "Спасибо") }
        assertTrue(commands.single().contains("send newReply"))
        assertFalse(commands.single().contains("visible of newReply to true"))
        assertFalse(commands.single().contains("activate"))
        assertFalse(commands.single().contains("display dialog"))
    }

    @Test
    fun `invalid recipients and message ids are rejected before Mail even when confirmed`() {
        assertFailsWith<BadInputException> {
            ToolMailSendNewMessage(bash).invoke(
                ToolMailSendNewMessage.Input(" ", send = true, confirmed = true), meta,
            )
        }
        assertFailsWith<BadInputException> {
            ToolMailReplyMessage(bash).invoke(
                ToolMailReplyMessage.Input(0, send = true, confirmed = true), meta,
            )
        }
        verify { bash wasNot Called }
    }

    @Test
    fun `failed sends propagate without retrying or reporting success`() = runTest {
        every { bash.sh(any(), *anyVararg()) } throws ShellException("Mail unavailable", 1)

        assertFailsWith<ShellException> {
            ToolMailSendNewMessage(bash).suspendInvoke(
                ToolMailSendNewMessage.Input("ivan@example.com", send = true, confirmed = true), meta,
            )
        }
        assertFailsWith<ShellException> {
            ToolMailReplyMessage(bash).suspendInvoke(
                ToolMailReplyMessage.Input(42, send = true, confirmed = true), meta,
            )
        }
        verify(exactly = 2) { bash.sh(any(), *anyVararg()) }
    }
}
