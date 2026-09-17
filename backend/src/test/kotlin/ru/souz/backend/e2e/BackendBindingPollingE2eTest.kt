package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramChat
import ru.souz.backend.telegram.TelegramGetMeResponse
import ru.souz.backend.telegram.TelegramMessage
import ru.souz.backend.telegram.TelegramUpdate
import ru.souz.backend.telegram.TelegramUpdatesResponse
import ru.souz.backend.telegram.TelegramUser
import ru.souz.backend.vk.VkBotApi
import ru.souz.backend.vk.VkGroup
import ru.souz.backend.vk.VkLongPollResponse
import ru.souz.backend.vk.VkLongPollServer
import ru.souz.backend.vk.VkLongPollUpdate
import ru.souz.backend.vk.VkMessage
import ru.souz.backend.vk.VkMessageObjectWrapper

class BackendBindingPollingE2eTest {
    @Test
    fun `idle bindings beyond the processing limit do not block another binding linking on its second poll`() {
        for (channel in listOf("telegram", "vk")) {
            val idlePolls = Channel<Unit>(Channel.UNLIMITED)
            val telegramUpdates = Channel<TelegramUpdatesResponse>()
            val vkUpdates = Channel<VkLongPollResponse>()
            val telegram = mockk<TelegramBotApi>(relaxed = true)
            val vk = mockk<VkBotApi>(relaxed = true)
            coEvery { telegram.getMe(any()) } returns TelegramGetMeResponse(
                ok = true, result = TelegramUser(1, isBot = true, firstName = "Test", username = "testbot"),
            )
            coEvery { telegram.getUpdates(any(), any(), any(), any()) } coAnswers {
                if (firstArg<String>() != "100005:token") {
                    idlePolls.send(Unit)
                    awaitCancellation()
                }
                telegramUpdates.receive()
            }
            coEvery { vk.getGroupInfo(any()) } coAnswers {
                VkGroup(firstArg<String>().substringBefore(':').toLong(), "Test")
            }
            coEvery { vk.getLongPollServer(any(), any()) } coAnswers {
                VkLongPollServer("key", firstArg(), "1")
            }
            coEvery { vk.pollLongPoll(any(), any(), any(), any()) } coAnswers {
                if (firstArg<String>() != "100005:token") {
                    idlePolls.send(Unit)
                    awaitCancellation()
                }
                vkUpdates.receive()
            }
            backendE2eTest(
                "${channel}_independent_polls",
                featureFlags = BackendFeatureFlags(telegramBot = channel == "telegram", vkBot = channel == "vk"),
                telegramApi = telegram.takeIf { channel == "telegram" },
                vkApi = vk.takeIf { channel == "vk" },
                startBackgroundServices = true,
            ) {
                val user = UUID.randomUUID().toString()
                for (index in 0..5) {
                    val chat = createPublicChat(user, "binding-$index")
                    val path = if (channel == "telegram") BackendHttpRoutes.chatTelegramBot(chat)
                        else BackendHttpRoutes.chatVkBot(chat)
                    val bound = client.put(path) {
                        trusted(user)
                        jsonBody("""{"token":"${100000 + index}:token"}""")
                    }
                    assertEquals(HttpStatusCode.OK, bound.status)
                    withTimeout(10_000) {
                        if (index < 5) {
                            idlePolls.receive()
                        } else {
                            val secret = bound.jsonBody()["pendingLinkCommand"].asText()
                            if (channel == "telegram") {
                                telegramUpdates.send(TelegramUpdatesResponse(ok = true))
                                telegramUpdates.send(TelegramUpdatesResponse(ok = true, result = listOf(
                                    TelegramUpdate(1, TelegramMessage(1, TelegramUser(701, firstName = "Test"), TelegramChat(701, "private"), secret)),
                                )))
                            } else {
                                vkUpdates.send(VkLongPollResponse("2"))
                                vkUpdates.send(VkLongPollResponse("3", listOf(
                                    VkLongPollUpdate("message_new", VkMessageObjectWrapper(VkMessage(1, 701, 701, secret))),
                                )))
                            }
                        }
                    }
                    if (index == 5) eventually("$channel linking while five other bindings idle") {
                        client.get(path) { trusted(user) }.jsonBody()["${channel}Bot"]
                            .takeIf { it["linked"].asBoolean() }
                    }
                }
            }
        }
    }
}
