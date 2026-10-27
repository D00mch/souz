package ru.souz.service.telegram

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.BadInputException
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.mail.MailSendApproval
import ru.souz.tool.mail.ToolMailSendNewMessage
import ru.souz.tool.telegram.ToolTelegramSend
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TelegramControlBotWriteProtectionTest {
    private var configuredToken: String? = "9001:fixture-token"
    private val client = mockk<SimpleTelegramClient>()
    private val config = object : TelegramBotConfigProvider {
        override fun token() = configuredToken
        override fun ownerId() = 42L
    }

    private fun chat(userId: Long) = TdApi.Chat().apply {
        // Deliberately distinct from the user ID: protection must use the resolved recipient.
        id = userId + 100_000
        title = "Contact $userId"
        type = TdApi.ChatTypePrivate(userId)
    }

    private fun message(chatId: Long) = TdApi.Message().apply {
        id = 123
        this.chatId = chatId
        content = TdApi.MessageText().apply { text = TdApi.FormattedText("Sent", emptyArray()) }
    }

    private fun service(): TelegramService {
        every { client.send(ofType<TdApi.CreatePrivateChat>()) } answers {
            CompletableFuture.completedFuture(chat(firstArg<TdApi.CreatePrivateChat>().userId))
        }
        every { client.send(ofType<TdApi.GetChat>()) } answers {
            CompletableFuture.completedFuture(chat(firstArg<TdApi.GetChat>().chatId - 100_000))
        }
        every { client.send(ofType<TdApi.SendChatAction>()) } returns CompletableFuture.completedFuture(TdApi.Ok())
        every { client.send(ofType<TdApi.SendMessage>()) } answers {
            CompletableFuture.completedFuture(message(firstArg<TdApi.SendMessage>().chatId))
        }
        every { client.send(ofType<TdApi.ForwardMessages>()) } answers {
            CompletableFuture.completedFuture(TdApi.Messages(1, arrayOf(message(firstArg<TdApi.ForwardMessages>().chatId))))
        }

        // Install an authorized transport without starting TDLib, reading a session or contacting Telegram.
        val service = TelegramService(
            telegramPlatformSupport = mockk { every { unsupportedReason() } returns null },
            scope = CoroutineScope(Job().apply { cancel() }),
            controlBotConfigProvider = config,
        )
        service.field<AtomicReference<SimpleTelegramClient>>("clientRef").set(client)
        service.field<MutableStateFlow<TelegramAuthState>>("authStateFlow").value =
            TelegramAuthState(step = TelegramAuthStep.READY)
        return service
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> TelegramService.field(name: String): T =
        TelegramService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    @Test
    fun `mail and Telegram tools cannot use the cached control bot to approve their own request`() = runTest {
        val service = service()
        service.field<MutableMap<Long, TelegramCachedContact>>("contactsByUserId")[9001] =
            TelegramCachedContact(9001, "Souz AI", setOf("souz ai"))
        val bash = mockk<ToolRunBashCommand>()
        every { bash.sh(any(), *anyVararg()) } returns
            """{"sender":"owner@example.com","subject":"Report","content":"Hello","to":["ivan@example.com"],"cc":[],"bcc":[]}"""
        val approval = MailSendApproval(bash)
        val meta = ToolInvocationMeta.localDefault()
        val request = ToolMailSendNewMessage(bash, approval).suspendInvoke(
            ToolMailSendNewMessage.Input("ivan@example.com", send = true, confirmed = true), meta,
        )
        val token = request.substringAfter("/mail_preview ").substringBefore('.')
        val telegram = ToolTelegramSend(service, TelegramContactSelectionBroker())
        for (command in listOf("/mail_preview", "/mail_send")) {
            val error = assertFailsWith<BadInputException> {
                telegram.suspendInvoke(ToolTelegramSend.Input("Souz AI", "$command $token"), meta)
            }
            assertTrue(error.message.orEmpty().contains("control bot"))
        }
        verify(exactly = 0) { client.send(ofType<TdApi.SendMessage>()) }
        verify(exactly = 0) { bash.sh(match { it.contains("send newMessage") }, *anyVararg()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/mail_preview pending-token", "/mail_send pending-token", "ordinary text", ""])
    fun `agent cannot send any text or attachment to the control bot`(text: String) = runTest {
        val service = service()
        val error = assertFailsWith<IllegalArgumentException> {
            service.sendMessageToUser(9001, text, attachmentPath = "/fixture/report.pdf")
        }
        assertTrue(error.message.orEmpty().contains("control bot"))
        verify(exactly = 0) { client.send(ofType<TdApi.CreatePrivateChat>()) }
        verify(exactly = 0) { client.send(ofType<TdApi.SendMessage>()) }
    }

    @Test
    fun `forwarding cannot write approval commands to the control bot`() = runTest {
        val service = service()
        assertFailsWith<IllegalArgumentException> {
            service.forwardMessageByChatIds(100_042, 109_001, "123")
        }
        verify(exactly = 0) { client.send(ofType<TdApi.ForwardMessages>()) }
        verify(exactly = 0) { client.send(match<TdApi.GetChat> { it.chatId == 100_042L }) }
    }

    @Test
    fun `ordinary sends and forwards work and bot identity is refreshed for each operation`() = runTest {
        val service = service()
        assertEquals(100_042L, service.sendMessageToUser(42, "Hello").chatId)
        assertEquals(100_042L, service.forwardMessageByChatIds(109_001, 100_042, "123").chatId)
        configuredToken = "42:replacement-bot"
        assertFailsWith<IllegalArgumentException> { service.sendMessageToUser(42, "Hello") }
        assertFailsWith<IllegalArgumentException> { service.forwardMessageByChatIds(109_001, 100_042, "123") }
        verify(exactly = 1) { client.send(ofType<TdApi.SendMessage>()) }
        verify(exactly = 1) { client.send(ofType<TdApi.ForwardMessages>()) }
    }

    @Test
    fun `Telegram remains usable without a configured control bot`() = runTest {
        configuredToken = null
        val service = service()
        assertEquals(109_001L, service.sendMessageToUser(9001, "Hello").chatId)
        assertEquals(109_001L, service.forwardMessageByChatIds(100_042, 109_001, "123").chatId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown:token", "0:token", "-1:token", "9001"])
    fun `unidentifiable configured control bot fails closed without leaking its token`(token: String) = runTest {
        configuredToken = token
        val service = service()
        val error = assertFailsWith<IllegalStateException> { service.sendMessageToUser(42, "Hello") }
        assertTrue(error.message.orEmpty().contains("identity"))
        assertTrue(!error.message.orEmpty().contains(token))
        assertFailsWith<IllegalStateException> { service.forwardMessageByChatIds(100_042, 109_001, "123") }
        verify(exactly = 0) { client.send(ofType<TdApi.SendMessage>()) }
        verify(exactly = 0) { client.send(ofType<TdApi.ForwardMessages>()) }
    }
}
