package ru.souz.service.telegram

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TelegramHistoryServiceTest {
    private val client = mockk<SimpleTelegramClient>()

    private fun service(): TelegramService {
        every { client.send(ofType<TdApi.OpenChat>()) } returns CompletableFuture.completedFuture(TdApi.Ok())
        every { client.send(ofType<TdApi.CloseChat>()) } returns CompletableFuture.completedFuture(TdApi.Ok())
        val service = TelegramService(
            telegramPlatformSupport = mockk { every { unsupportedReason() } returns null },
            scope = CoroutineScope(Job().apply { cancel() }),
            controlBotConfigProvider = object : TelegramBotConfigProvider {
                override fun token(): String? = null
                override fun ownerId(): Long? = null
            },
        )
        service.field<AtomicReference<SimpleTelegramClient>>("clientRef").set(client)
        service.field<MutableStateFlow<TelegramAuthState>>("authStateFlow").value =
            TelegramAuthState(step = TelegramAuthStep.READY)
        return service
    }

    @Test
    fun `short overlapping TDLib pages fill bounded history and cached idle events need no request`() = runTest {
        val service = service()
        val requests = mutableListOf<TdApi.GetChatHistory>()
        every { client.send(ofType<TdApi.GetChatHistory>()) } answers {
            val request = firstArg<TdApi.GetChatHistory>()
            requests += request
            val newest = if (request.fromMessageId == 0L) 700L else request.fromMessageId
            val count = minOf(request.limit, 37)
            CompletableFuture.completedFuture(TdApi.Messages(count,
                (newest downTo newest - count + 1).map { message(it) }.toTypedArray()))
        }
        val initial = service.getHistoryByChatId(1, 900)
        assertEquals(500, initial.size)
        assertEquals(700L, initial.first().messageId)
        assertEquals(201L, initial.last().messageId)
        assertTrue(requests.size > 5)
        assertTrue(requests.all { it.limit <= 100 && it.offset == 0 && !it.onlyLocal })
        val fetchCount = requests.size
        val cache = service.field<TelegramHistoryCache>("historyCache")
        cache.onUpdate(client, TdApi.UpdateNewMessage(message(701)))
        val result = service.getHistoryByChatId(1, 500, forceRefresh = false)
        assertEquals(701L, result.first().messageId)
        assertEquals(202L, result.last().messageId)
        assertEquals(fetchCount, requests.size)
        verify(exactly = 1) { client.send(ofType<TdApi.OpenChat>()) }
        verify(exactly = 0) { client.send(ofType<TdApi.CloseChat>()) }
        verify(exactly = 0) { client.send(ofType<TdApi.ViewMessages>()) }
    }

    @Test
    fun `duplicate page terminates and forced refresh replaces while ordinary fetch merges`() = runTest {
        val service = service()
        every { client.send(ofType<TdApi.GetChatHistory>()) } returns
            CompletableFuture.completedFuture(TdApi.Messages(1, arrayOf(message(10))))
        assertEquals(listOf(10L), service.getHistoryByChatId(1, 3).map { it.messageId })
        verify(exactly = 2) { client.send(ofType<TdApi.GetChatHistory>()) }
        every { client.send(ofType<TdApi.GetChatHistory>()) } returns
            CompletableFuture.completedFuture(TdApi.Messages(1, arrayOf(message(11))))
        assertEquals(listOf(11L, 10L), service.getHistoryByChatId(1, 3).map { it.messageId })
        assertEquals(listOf(11L), service.getHistoryByChatId(1, 1, true).map { it.messageId })
        every { client.send(ofType<TdApi.GetChatHistory>()) } returns
            CompletableFuture.completedFuture(TdApi.Messages(0, emptyArray()))
        assertTrue(service.getHistoryByChatId(1, 1, true).isEmpty())
    }

    @Test
    fun `content updates preserve sender date and inline keyboard in rendered cached messages`() = runTest {
        val service = service()
        val original = message(10).apply {
            date = 123
            senderId = TdApi.MessageSenderChat(42)
            replyMarkup = TdApi.ReplyMarkupInlineKeyboard(arrayOf(arrayOf(
                TdApi.InlineKeyboardButton("fixture button", TdApi.InlineKeyboardButtonTypeUrl("https://example.test"))
            )))
        }
        every { client.send(ofType<TdApi.GetChatHistory>()) } returns
            CompletableFuture.completedFuture(TdApi.Messages(1, arrayOf(original)))
        val before = service.getHistoryByChatId(1, 1).single()
        service.field<TelegramHistoryCache>("historyCache").onUpdate(client,
            TdApi.UpdateMessageContent(1, 10, TdApi.MessageText().apply {
                text = TdApi.FormattedText("edited", emptyArray())
            }))
        val after = service.getHistoryByChatId(1, 1).single()
        assertEquals(before.copy(text = "edited\nfixture button"), after)
        assertEquals("42", after.sender)
        assertEquals(123, after.unixTime)
        assertFalse(after.text.orEmpty().contains("fixture 10"))
    }

    @Test
    fun `late send response cannot reinsert a temporary message after its completion event`() = runTest {
        val service = service()
        every { client.send(ofType<TdApi.GetChatHistory>()) } returns
            CompletableFuture.completedFuture(TdApi.Messages(1, arrayOf(message(10))))
        service.getHistoryByChatId(1, 1)
        every { client.send(ofType<TdApi.CreatePrivateChat>()) } returns
            CompletableFuture.completedFuture(TdApi.Chat().apply { id = 1; type = TdApi.ChatTypePrivate(42) })
        every { client.send(ofType<TdApi.SendChatAction>()) } returns CompletableFuture.completedFuture(TdApi.Ok())
        val sent = CompletableFuture<TdApi.Message>()
        every { client.send(ofType<TdApi.SendMessage>()) } returns sent
        val request = async { service.sendMessageToUser(42, "synthetic outgoing") }
        runCurrent()
        val cache = service.field<TelegramHistoryCache>("historyCache")
        cache.onUpdate(client, TdApi.UpdateNewMessage(message(19)))
        cache.onUpdate(client, TdApi.UpdateMessageSendSucceeded(message(20), 19))
        sent.complete(message(19))
        request.await()
        assertEquals(listOf(20L, 10L), service.getHistoryByChatId(1, 2).map { it.messageId })
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> TelegramService.field(name: String): T =
        TelegramService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun message(id: Long) = TdApi.Message().apply {
        chatId = 1
        this.id = id
        content = TdApi.MessageText().apply { text = TdApi.FormattedText("fixture $id", emptyArray()) }
    }
}
