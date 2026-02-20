package ru.souz.service.telegram

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.agent.GraphBasedAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelegramBotControllerTest {

    @Test
    fun `processUpdates executes only owner private commands`() = runTest {
        val agent = mockk<GraphBasedAgent>()
        coEvery { agent.execute("ping") } returns "pong"

        val botApi = FakeBotApi()
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true),
            agent = agent,
            botApi = botApi,
        )

        val nextOffset = controller.processUpdates(
            token = "token",
            ownerId = 42,
            updates = listOf(
                TelegramUpdate(
                    updateId = 5,
                    message = TelegramMessage(
                        messageId = 1,
                        from = TelegramUser(id = 42, isBot = false),
                        chat = TelegramChat(id = 100, type = "private"),
                        text = "ping",
                    ),
                ),
            ),
            currentOffset = 0,
        )

        assertEquals(6, nextOffset)
        assertEquals(listOf("Processing: ping", "pong"), botApi.sentTexts)
        coVerify(exactly = 1) { agent.execute("ping") }

        controller.close()
    }

    @Test
    fun `processUpdates rejects owner commands outside private chat`() = runTest {
        val agent = mockk<GraphBasedAgent>(relaxed = true)
        val botApi = FakeBotApi()
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true),
            agent = agent,
            botApi = botApi,
        )

        val nextOffset = controller.processUpdates(
            token = "token",
            ownerId = 42,
            updates = listOf(
                TelegramUpdate(
                    updateId = 10,
                    message = TelegramMessage(
                        messageId = 1,
                        from = TelegramUser(id = 42, isBot = false),
                        chat = TelegramChat(id = -1001, type = "group"),
                        text = "dangerous",
                    ),
                ),
            ),
            currentOffset = 0,
        )

        assertEquals(11, nextOffset)
        assertTrue(botApi.sentTexts.isEmpty())
        coVerify(exactly = 0) { agent.execute(any()) }

        controller.close()
    }

    @Test
    fun `processUpdates rejects non-owner private commands`() = runTest {
        val agent = mockk<GraphBasedAgent>(relaxed = true)
        val botApi = FakeBotApi()
        val controller = TelegramBotController(
            telegramService = mockk(relaxed = true),
            agent = agent,
            botApi = botApi,
        )

        val nextOffset = controller.processUpdates(
            token = "token",
            ownerId = 42,
            updates = listOf(
                TelegramUpdate(
                    updateId = 20,
                    message = TelegramMessage(
                        messageId = 1,
                        from = TelegramUser(id = 99, isBot = false),
                        chat = TelegramChat(id = 99, type = "private"),
                        text = "ping",
                    ),
                ),
            ),
            currentOffset = 0,
        )

        assertEquals(21, nextOffset)
        assertTrue(botApi.sentTexts.isEmpty())
        coVerify(exactly = 0) { agent.execute(any()) }

        controller.close()
    }

    private class FakeBotApi : TelegramBotApi {
        val sentTexts = mutableListOf<String>()

        override suspend fun getUpdates(token: String, offset: Long, timeoutSeconds: Int): TelegramUpdatesResponse {
            error("getUpdates is not used in this test")
        }

        override suspend fun sendMessage(token: String, chatId: Long, text: String) {
            sentTexts += text
        }
    }
}
