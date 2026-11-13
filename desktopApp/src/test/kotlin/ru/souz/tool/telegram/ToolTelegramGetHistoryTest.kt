package ru.souz.tool.telegram

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.service.telegram.TelegramChatCandidate
import ru.souz.service.telegram.TelegramChatLookupResult
import ru.souz.service.telegram.TelegramChatSelectionBroker
import ru.souz.service.telegram.TelegramMessageView
import ru.souz.service.telegram.TelegramService
import kotlin.test.assertEquals

class ToolTelegramGetHistoryTest {

    private val telegramService = mockk<TelegramService>()
    private val tool = ToolTelegramGetHistory(
        telegramService = telegramService,
        chatSelectionBroker = TelegramChatSelectionBroker(),
    )

    @ParameterizedTest(name = "default options: {0}")
    @ValueSource(booleans = [true, false])
    fun `history forwards options and serializes populated or empty results`(defaultOptions: Boolean) = runTest {
        val input = if (defaultOptions) {
            ToolTelegramGetHistory.Input(chatName = "Проект Альфа")
        } else {
            ToolTelegramGetHistory.Input(chatName = "Проект Альфа", limit = 25, forceRefresh = false)
        }
        val expectedLimit = if (defaultOptions) 100 else 25
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
        ).take(if (defaultOptions) 1 else 0)

        coEvery {
            telegramService.resolveChatTarget("Проект Альфа")
        } returns TelegramChatLookupResult.Resolved(candidate)
        coEvery {
            telegramService.getHistoryByChatId(42L, expectedLimit, defaultOptions)
        } returns history

        val result = restJsonMapper.readTree(
            tool.suspendInvoke(
                input,
                ToolInvocationMeta.localDefault(),
            )
        )

        assertEquals(history.size, result.path("count").asInt())
        assertEquals(42L, result.path("chatId").asLong())
        assertEquals("Проект Альфа", result.path("chatTitle").asText())
        assertEquals(defaultOptions, result.path("forceRefresh").asBoolean())
        assertEquals(history.map { it.text }, result.path("messages").map { it.path("text").asText() })

        coVerify(exactly = 1) { telegramService.resolveChatTarget("Проект Альфа") }
        coVerify(exactly = 1) { telegramService.getHistoryByChatId(42L, expectedLimit, defaultOptions) }
    }

}
