package ru.souz.tool.telegram

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.service.telegram.TelegramChatCandidate
import ru.souz.service.telegram.TelegramChatLookupResult
import ru.souz.service.telegram.TelegramMessageView
import ru.souz.service.telegram.TelegramService
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolTelegramGetHistoryTest {

    private val telegramService = mockk<TelegramService>()
    private val tool = ToolTelegramGetHistory(
        telegramService = telegramService,
        chatSelectionBroker = TelegramChatSelectionBroker(),
    )

    @Test
    fun `loads 100 messages with force refresh by default`() = runTest {
        val candidate = TelegramChatCandidate(
            chatId = 42L,
            title = "Проект Альфа",
            unreadCount = 0,
            linkedUserId = null,
            lastMessageText = "Последний апдейт",
            score = 980,
        )
        val history = listOf(
            TelegramMessageView(
                chatId = 42L,
                chatTitle = "Проект Альфа",
                messageId = 1001L,
                sender = "Иван",
                unixTime = 1_710_000_000L,
                text = "Привет",
            )
        )

        coEvery {
            telegramService.resolveChatTarget("Проект Альфа")
        } returns TelegramChatLookupResult.Resolved(candidate)
        coEvery {
            telegramService.getHistoryByChatId(42L, 100, true)
        } returns history

        val result = restJsonMapper.readTree(
            tool.suspendInvoke(
                ToolTelegramGetHistory.Input(chatName = "Проект Альфа"),
                ToolInvocationMeta.localDefault(),
            )
        )

        assertEquals(1, result.path("count").asInt())
        assertEquals(42L, result.path("chatId").asLong())
        assertEquals("Проект Альфа", result.path("chatTitle").asText())
        assertTrue(result.path("forceRefresh").asBoolean())
        assertEquals("Привет", result.path("messages").first().path("text").asText())

        coVerify(exactly = 1) { telegramService.resolveChatTarget("Проект Альфа") }
        coVerify(exactly = 1) { telegramService.getHistoryByChatId(42L, 100, true) }
    }

    @Test
    fun `passes explicit limit and force refresh flag`() = runTest {
        val candidate = TelegramChatCandidate(
            chatId = 7L,
            title = "Работа",
            unreadCount = 3,
            linkedUserId = null,
            lastMessageText = "Тест",
            score = 990,
        )

        coEvery {
            telegramService.resolveChatTarget("Работа")
        } returns TelegramChatLookupResult.Resolved(candidate)
        coEvery {
            telegramService.getHistoryByChatId(7L, 25, false)
        } returns emptyList()

        val result = restJsonMapper.readTree(
            tool.suspendInvoke(
                ToolTelegramGetHistory.Input(
                    chatName = "Работа",
                    limit = 25,
                    forceRefresh = false,
                ),
                ToolInvocationMeta.localDefault(),
            )
        )

        assertEquals(0, result.path("count").asInt())
        assertEquals(7L, result.path("chatId").asLong())
        assertEquals("Работа", result.path("chatTitle").asText())
        assertTrue(!result.path("forceRefresh").asBoolean())

        coVerify(exactly = 1) { telegramService.getHistoryByChatId(7L, 25, false) }
    }
}
