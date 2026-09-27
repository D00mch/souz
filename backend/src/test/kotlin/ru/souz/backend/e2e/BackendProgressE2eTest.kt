package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.telegram.*
import ru.souz.backend.vk.*
import ru.souz.llms.LLMMessageRole

class BackendProgressE2eTest {
    @Test
    fun `bot progress is optional transient ordered and cannot block or fail the agent`() {
        for (telegram in listOf(true, false)) for (mode in listOf("disabled", "delivered", "failed", "blocked")) {
            val enabled = mode != "disabled"
            val sent = CopyOnWriteArrayList<String>()
            val attempted = CopyOnWriteArrayList<String>()
            val sending = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val nextLlm = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val send: suspend (String) -> Unit = { text ->
                if (text.startsWith("Checking")) {
                    attempted += text
                    sending.complete(Unit)
                    when (mode) {
                        "failed" -> throw IOException("Unavailable")
                        "blocked" -> try { awaitCancellation() } finally { stopped.complete(Unit) }
                    }
                }
                sent += text
            }
            var telegramUpdates = emptyList<TelegramUpdate>()
            var vkUpdates = emptyList<VkLongPollUpdate>()
            val tg = mockk<TelegramBotApi>(relaxed = true) {
                coEvery { getMe(any()) } returns TelegramGetMeResponse(
                    ok = true, result = TelegramUser(123456, isBot = true, firstName = "Bot", username = "bot"),
                )
                coEvery { getUpdates(any(), any(), any(), any()) } coAnswers {
                    TelegramUpdatesResponse(ok = true, result = telegramUpdates)
                }
                coEvery { sendMessage(any(), any(), any()) } coAnswers { send(thirdArg()) }
            }
            val vk = mockk<VkBotApi>(relaxed = true) {
                coEvery { getGroupInfo(any()) } returns VkGroup(123, "Group")
                coEvery { getUserInfo(any(), any()) } returns VkUser(701, "User", "Name")
                coEvery { getLongPollServer(any(), any()) } returns VkLongPollServer("key", "https://vk.test", "1")
                coEvery { pollLongPoll(any(), any(), any(), any()) } coAnswers { VkLongPollResponse("2", vkUpdates) }
                coEvery { sendMessage(any(), any(), any(), any()) } coAnswers { send(thirdArg()) }
            }
            backendE2eTest(
                "progress_${telegram}_$mode",
                featureFlags = BackendFeatureFlags(wsEvents = true, telegramBot = telegram, vkBot = !telegram),
                telegramApi = tg.takeIf { telegram },
                vkApi = vk.takeUnless { telegram },
                llm = E2eLlmApi { request ->
                    val system = request.messages.first().content
                    assertTrue("custom base" in system)
                    val instruction = if (telegram) "Before every tool call" else "Перед каждым вызовом инструментов"
                    assertEquals(enabled, instruction in system)
                    if (request.messages.any { it.role == LLMMessageRole.function }) {
                        nextLlm.complete(Unit)
                        finish.await()
                        reply(request, "Done")
                    } else {
                        val tool = toolCallReply(request, "GetSkillByName", mapOf("skillId" to "ListActiveChannels"))
                        tool.copy(choices = (listOf("Checking one", "Checking two").flatMap { reply(request, it).choices } + tool.choices)
                            .mapIndexed { index, choice -> choice.copy(index = index) })
                    }
                },
            ) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                assertFalse(client.get(BackendHttpRoutes.SETTINGS) { trusted(user) }
                    .jsonBody()["settings"]["narrateSteps"].asBoolean())
                client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user)
                    jsonBody("""{"narrateSteps":$enabled,"locale":"${if (telegram) "en-US" else "ru-RU"}","systemPrompt":"custom base"}""")
                }
                val path = if (telegram) BackendHttpRoutes.chatTelegramBot(chat) else BackendHttpRoutes.chatVkBot(chat)
                val secret = client.put(path) {
                    trusted(user); jsonBody("""{"token":"123456:progress-token"}""")
                }.jsonBody()["pendingLinkCommand"].asText()
                fun enqueue(id: Long, text: String) {
                    telegramUpdates = listOf(TelegramUpdate(id, TelegramMessage(
                        messageId = id, from = TelegramUser(701, firstName = "User"),
                        chat = TelegramChat(701, "private"), text = text,
                    )))
                    vkUpdates = listOf(VkLongPollUpdate("message_new", VkMessageObjectWrapper(VkMessage(id, 701, 701, text))))
                }
                suspend fun poll() { if (telegram) backend.pollTelegramOnce() else backend.pollVkOnce() }
                enqueue(1, secret)
                poll()
                sent.clear()
                enqueue(2, "Check it")
                withTimeout(20_000) {
                    val polling = async { poll() }
                    try {
                        nextLlm.await() // Tools and the next model request proceed even if the send hangs.
                        if (enabled) {
                            sending.await()
                            if (mode != "blocked") eventually("both separate progress blocks attempted") {
                                attempted.toList().takeIf { it.size == 2 }
                            }
                        }
                    } finally {
                        finish.complete(Unit)
                    }
                    polling.await()
                }
                if (mode == "blocked") assertTrue(stopped.isCompleted)
                assertEquals(if (mode == "delivered") listOf("Checking one", "Checking two", "Done") else listOf("Done"), sent.toList())
                val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
                assertEquals(listOf("Check it", "Done"), messages.map { it["content"].asText() })
                val events = client.get(BackendHttpRoutes.chatEvents(chat)) { trusted(user) }.jsonBody()["items"]
                assertFalse(events.any { it["type"].asText() == "assistant.message" })
            }
        }
    }
}
