package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.vk.VkApiError
import ru.souz.backend.vk.VkBotApi
import ru.souz.backend.vk.VkGetGroupResponse
import ru.souz.backend.vk.VkGroup
import ru.souz.backend.vk.VkLongPollResponse
import ru.souz.backend.vk.VkLongPollServer
import ru.souz.backend.vk.VkLongPollServerResponse
import ru.souz.backend.vk.VkLongPollUpdate
import ru.souz.backend.vk.VkMessage
import ru.souz.backend.vk.VkMessageObjectWrapper
import ru.souz.backend.vk.VkUser
import ru.souz.backend.vk.VkUsersGetResponse
import ru.souz.llms.LLMMessageRole

class BackendVkE2eTest {
    @Test
    fun `vk routes validate redact encrypt and enforce chat ownership`() =
        backendE2eTest(
            schemaPrefix = "e2e_vk",
            featureFlags = BackendFeatureFlags(wsEvents = true, vkBot = true),
            vkApi = FakeVkBotApi(),
        ) {
            val userId = UUID.randomUUID().toString()
            val foreignUserId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId, "create-1")
            val foreignChatId = createPublicChat(foreignUserId, "create-2")
            val token = "vk1:valid-token"

            val invalid = client.put(BackendHttpRoutes.chatVkBot(chatId)) {
                trusted(userId)
                jsonBody("""{"token":"bad-token"}""")
            }
            val upserted = client.put(BackendHttpRoutes.chatVkBot(chatId)) {
                trusted(userId)
                jsonBody("""{"token":"$token"}""")
            }
            val fetched = client.get(BackendHttpRoutes.chatVkBot(chatId)) {
                trusted(userId)
            }
            val foreign = client.get(BackendHttpRoutes.chatVkBot(chatId)) {
                trusted(foreignUserId)
            }
            val conflict = client.put(BackendHttpRoutes.chatVkBot(foreignChatId)) {
                trusted(foreignUserId)
                jsonBody("""{"token":"$token"}""")
            }

            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("invalid_vk_bot_token", invalid.jsonBody()["error"]["code"].asText())
            assertEquals(HttpStatusCode.OK, upserted.status)
            val upsertPayload = upserted.jsonBody()
            assertEquals("Souz E2E", upsertPayload["vkBot"]["vkGroupName"].asText())
            assertTrue(upsertPayload["pendingLinkCommand"].asText().isNotBlank())
            assertFalse(upserted.bodyAsText().contains(token))
            assertEquals(HttpStatusCode.OK, fetched.status)
            assertFalse(fetched.bodyAsText().contains(token))
            assertEquals(false, fetched.jsonBody()["vkBot"]["linked"].asBoolean())
            assertEquals(HttpStatusCode.NotFound, foreign.status)
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals("vk_bot_already_bound", conflict.jsonBody()["error"]["code"].asText())

            assertFalse(
                sql { connection ->
                    connection.prepareStatement(
                        "select group_token_encrypted from vk_bot_bindings where chat_id = ?"
                    ).use { statement ->
                        statement.setObject(1, UUID.fromString(chatId))
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getString(1).contains(token)
                        }
                    }
                }
            )
        }

    @Test
    fun `polling links once rejects foreign senders and executes each update once`() {
        val vkApi = FakeVkBotApi()
        backendE2eTest(
            schemaPrefix = "e2e_vk_polling",
            featureFlags = BackendFeatureFlags(wsEvents = true, vkBot = true),
            vkApi = vkApi,
            startBackgroundServices = true,
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId, "create-polling")
            val settings = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}"}""")
            }
            assertEquals(HttpStatusCode.OK, settings.status)
            val token = "vk1:polling-token"
            val upserted = client.put(BackendHttpRoutes.chatVkBot(chatId)) {
                trusted(userId)
                jsonBody("""{"token":"$token"}""")
            }
            assertEquals(HttpStatusCode.OK, upserted.status)
            val linkCommand = upserted.jsonBody()["pendingLinkCommand"].asText()
            assertTrue(linkCommand.isNotBlank())

            vkApi.enqueue(update(10, senderId = 701, peerId = 701 + GROUP_PEER_OFFSET, text = linkCommand))
            eventually("group-like update checkpoint") {
                vkApi.requestedTs.lastOrNull()?.toLongOrNull()?.takeIf { it >= 11 }
            }
            assertFalse(binding(userId, chatId)["linked"].asBoolean())
            assertFalse(vkApi.sentMessages.any { it.peerId == 701L + GROUP_PEER_OFFSET })

            vkApi.enqueue(update(11, senderId = 701, text = "wrong-secret"))
            eventually("invalid private link rejection") {
                vkApi.sentMessages.firstOrNull {
                    it.peerId == 701L && it.text == "Чтобы привязать этот чат, отправь секрет, который показал Souz."
                }
            }
            assertFalse(binding(userId, chatId)["linked"].asBoolean())

            vkApi.enqueue(update(12, senderId = 701, text = linkCommand))
            val linked = eventually("private VK link") {
                binding(userId, chatId).takeIf { it["linked"].asBoolean() }
            }
            assertTrue(vkApi.sentMessages.any {
                it.peerId == 701L && it.text == "Готово, этот VK-аккаунт привязан к чату Souz."
            })

            val discoveryChatId = createPublicChat(userId, "create-discovery")
            val archivedChatId = createPublicChat(userId, "create-archived")
            val foreignChatId = createPublicChat(UUID.randomUUID().toString(), "create-foreign")
            assertEquals(
                HttpStatusCode.OK,
                client.post(BackendHttpRoutes.archiveChat(archivedChatId)) {
                    trusted(userId)
                }.status,
            )
            llm.requestSkillForPrompt("discover vk channels", "ListActiveChannels", emptyMap())
            val discovery = client.post(BackendHttpRoutes.chatMessages(discoveryChatId)) {
                trusted(userId)
                jsonBody("""{"content":"discover vk channels","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
            }
            assertEquals(HttpStatusCode.OK, discovery.status)
            val channelResult = eventually("production channel discovery result") {
                llm.requests
                    .filter { request -> request.conversationPrompt() == "discover vk channels" }
                    .flatMap { request -> request.messages }
                    .lastOrNull { message ->
                        message.role == LLMMessageRole.function && message.name == "RunSkillCommand"
                    }
            }
            val channels = json.readTree(channelResult.content)["channels"]
            assertEquals(
                setOf(
                    "vk:$chatId",
                    "public_client:$discoveryChatId",
                ),
                channels.map { "${it["channelType"].asText()}:${it["channelId"].asText()}" }.toSet(),
            )
            assertTrue(channels.none { it["channelId"].asText() in setOf(archivedChatId, foreignChatId) })

            vkApi.enqueue(update(13, senderId = 701, text = "message from VK"))
            eventually("real-kernel VK turn") {
                llm.requests.count { it.conversationPrompt() == "message from VK" }.takeIf { it == 1 }
            }
            val messages = eventually("persisted VK turn") {
                client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].takeIf { it.size() == 2 }
            }
            assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })
            assertEquals("message from VK", messages.first()["content"].asText())
            val assistantReply = messages.last()["content"].asText()
            eventually("VK assistant reply") {
                vkApi.sentMessages.firstOrNull { it.peerId == 701L && it.text == assistantReply }
            }
            assertTrue(vkApi.typingActivity.any { it == 701L })

            eventually("a repeated empty poll after the VK checkpoint") {
                vkApi.requestedTs.count { it.toLongOrNull() == 14L }.takeIf { it >= 2 }
            }
            assertEquals(1, llm.requests.count { it.conversationPrompt() == "message from VK" })
            assertEquals(
                2,
                client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].size(),
            )

            vkApi.enqueue(update(14, senderId = 999, text = linkCommand))
            eventually("foreign VK sender rejection") {
                vkApi.sentMessages.firstOrNull {
                    it.peerId == 999L && it.text == "Этот бот уже привязан к другому VK-аккаунту."
                }
            }
            eventually("foreign update checkpoint") {
                vkApi.requestedTs.lastOrNull()?.toLongOrNull()?.takeIf { it >= 15 }
            }
            assertEquals(1, llm.requests.count { it.conversationPrompt() == "message from VK" })
            assertEquals(
                2,
                client.get(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                }.jsonBody()["items"].size(),
            )
        }
    }

    @Test
    fun `agent can deliver to linked VK and failed API send is not persisted as delivered`() {
        val vkApi = FakeVkBotApi()
        backendE2eTest(
            schemaPrefix = "e2e_vk_outbound",
            featureFlags = BackendFeatureFlags(wsEvents = true, vkBot = true),
            vkApi = vkApi,
            startBackgroundServices = true,
        ) {
            val userId = UUID.randomUUID().toString()
            val vkChatId = createPublicChat(userId, "create-vk-target")
            val sourceChatId = createPublicChat(userId, "create-vk-source")
            val failedSourceChatId = createPublicChat(userId, "create-vk-failure")
            val upserted = client.put(BackendHttpRoutes.chatVkBot(vkChatId)) {
                trusted(userId)
                jsonBody("""{"token":"vk1:outbound-token"}""")
            }
            assertEquals(HttpStatusCode.OK, upserted.status)
            val linkCommand = upserted.jsonBody()["pendingLinkCommand"].asText()
            vkApi.enqueue(update(20, senderId = 701, text = linkCommand))
            eventually("linked outbound VK binding") {
                binding(userId, vkChatId).takeIf { it["linked"].asBoolean() }
            }

            val deliveredText = "vk outbound delivery"
            llm.requestSkillForPrompt(
                prompt = "send vk outbound",
                skillId = "SendMessageToChannel",
                arguments = mapOf(
                    "channelType" to "vk",
                    "channelId" to vkChatId,
                    "text" to deliveredText,
                ),
            )
            val deliveredTurn = client.post(BackendHttpRoutes.chatMessages(sourceChatId)) {
                trusted(userId)
                jsonBody("""{"content":"send vk outbound","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
            }
            assertEquals(HttpStatusCode.OK, deliveredTurn.status)
            awaitTerminal(sourceChatId, userId)
            eventually("VK outbound message") {
                vkApi.sentMessages.firstOrNull { it.peerId == 701L && it.text == deliveredText }
            }
            val deliveredMessages = client.get(BackendHttpRoutes.chatMessages(vkChatId)) {
                trusted(userId)
            }.jsonBody()["items"]
            assertTrue(deliveredMessages.any { message ->
                message["content"].asText() == deliveredText &&
                    message.path("metadata").path("crossChannel").asText() == "true"
            })

            val failedText = "vk outbound should not persist"
            vkApi.failSendText(failedText)
            llm.requestSkillForPrompt(
                prompt = "send vk outbound failure",
                skillId = "SendMessageToChannel",
                arguments = mapOf(
                    "channelType" to "vk",
                    "channelId" to vkChatId,
                    "text" to failedText,
                ),
            )
            val failedTurn = client.post(BackendHttpRoutes.chatMessages(failedSourceChatId)) {
                trusted(userId)
                jsonBody("""{"content":"send vk outbound failure","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
            }
            assertEquals(HttpStatusCode.OK, failedTurn.status)
            awaitTerminal(failedSourceChatId, userId)

            assertTrue(vkApi.sentMessages.none { it.text == failedText })
            val messagesAfterFailure = client.get(BackendHttpRoutes.chatMessages(vkChatId)) {
                trusted(userId)
            }.jsonBody()["items"]
            assertTrue(messagesAfterFailure.none { it["content"].asText() == failedText })
            assertEquals(
                1,
                messagesAfterFailure.count { it.path("metadata").path("crossChannel").asText() == "true" },
            )
        }
    }

    @Test
    fun `polling discards an update after its database lease is stolen`() {
        val vkApi = FakeVkBotApi()
        val pausedPoll = vkApi.pauseNextPoll()
        backendE2eTest(
            schemaPrefix = "e2e_vk_lease_fence",
            featureFlags = BackendFeatureFlags(wsEvents = true, vkBot = true),
            vkApi = vkApi,
            startBackgroundServices = true,
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId, "create-lease")
            val upserted = client.put(BackendHttpRoutes.chatVkBot(chatId)) {
                trusted(userId)
                jsonBody("""{"token":"vk1:lease-token"}""")
            }
            assertEquals(HttpStatusCode.OK, upserted.status)
            val linkCommand = upserted.jsonBody()["pendingLinkCommand"].asText()

            eventually("paused VK poll holding a binding lease") {
                pausedPoll.takeIf { it.entered.isCompleted }
            }
            pausedPoll.respondWith(update(10, senderId = 701, text = linkCommand), newTs = "11")
            sql { connection ->
                connection.prepareStatement(
                    """
                    update vk_bot_bindings
                    set poller_owner = 'takeover-instance',
                        poller_lease_until = current_timestamp + interval '250 milliseconds'
                    where chat_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(chatId))
                    assertEquals(1, statement.executeUpdate())
                }
            }
            pausedPoll.release.complete(Unit)

            eventually("a later poll after the stolen lease expires") {
                vkApi.requestedTs.size.takeIf { it >= 2 }
            }
            assertFalse(binding(userId, chatId)["linked"].asBoolean())
            assertTrue(vkApi.sentMessages.isEmpty())
            // The polling loop keeps ticking for VK (empty-batch `ts` advances legitimately once the
            // stolen lease's short TTL expires and the real instance reclaims it), so the checkpoint
            // isn't pinned at null forever — the invariant under test is narrower: the *fenced*
            // batch's own `ts` ("11", returned alongside the discarded update) must never land,
            // since that write happened under a lease this instance no longer held.
            val checkpoint = sql { connection ->
                connection.prepareStatement(
                    "select last_ts from vk_bot_bindings where chat_id = ?"
                ).use { statement ->
                    statement.setObject(1, UUID.fromString(chatId))
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getString(1)
                    }
                }
            }
            assertTrue(checkpoint != "11")
        }
    }

    private suspend fun BackendE2eScope.binding(userId: String, chatId: String) =
        assertNotNull(
            client.get(BackendHttpRoutes.chatVkBot(chatId)) {
                trusted(userId)
            }.jsonBody()["vkBot"]
        )

    private suspend fun BackendE2eScope.awaitTerminal(chatId: String, userId: String) {
        eventually("terminal VK source execution") {
            client.get(BackendHttpRoutes.chatEvents(chatId)) {
                trusted(userId)
            }.jsonBody()["items"].takeIf { events ->
                events.any { event ->
                    event["type"].asText() in setOf(
                        "execution.finished",
                        "execution.failed",
                        "execution.cancelled",
                    )
                }
            }
        }
    }

    private fun update(
        id: Long,
        senderId: Long,
        peerId: Long = senderId,
        text: String,
    ) = VkLongPollUpdate(
        type = "message_new",
        obj = VkMessageObjectWrapper(
            message = VkMessage(id = id, fromId = senderId, peerId = peerId, text = text),
        ),
    )

    private companion object {
        const val GROUP_PEER_OFFSET = 2_000_000_000L
    }
}

private class FakeVkBotApi : VkBotApi {
    data class SentMessage(val peerId: Long, val text: String)

    val requestedTs = CopyOnWriteArrayList<String>()
    val sentMessages = CopyOnWriteArrayList<SentMessage>()
    val typingActivity = CopyOnWriteArrayList<Long>()
    private val updates = CopyOnWriteArrayList<VkLongPollUpdate>()
    private val failedSendTexts = CopyOnWriteArrayList<String>()
    private val nextPollPause = AtomicReference<PausedVkPoll?>()

    fun enqueue(update: VkLongPollUpdate) {
        updates += update
    }

    fun failSendText(text: String) {
        failedSendTexts += text
    }

    fun pauseNextPoll(): PausedVkPoll =
        PausedVkPoll().also { pause -> check(nextPollPause.compareAndSet(null, pause)) }

    override suspend fun getGroupInfo(groupToken: String): VkGetGroupResponse =
        if (groupToken.startsWith("bad")) {
            VkGetGroupResponse(error = VkApiError(errorCode = 100, errorMsg = "Invalid token"))
        } else {
            VkGetGroupResponse(response = listOf(VkGroup(id = 555L, name = "Souz E2E")))
        }

    override suspend fun getLongPollServer(groupToken: String, groupId: Long): VkLongPollServerResponse =
        VkLongPollServerResponse(response = VkLongPollServer(key = "key-1", server = "https://example.test/lp", ts = "1"))

    override suspend fun getUserInfo(groupToken: String, userId: Long): VkUsersGetResponse =
        VkUsersGetResponse(response = listOf(VkUser(id = userId, firstName = "E2E", lastName = null)))

    override suspend fun pollLongPoll(server: String, key: String, ts: String, waitSeconds: Int): VkLongPollResponse {
        requestedTs += ts
        val pause = nextPollPause.getAndSet(null)
        if (pause != null) {
            pause.entered.complete(Unit)
            pause.release.await()
            return pause.response()
        }
        val offset = ts.toLongOrNull() ?: 1L
        val matched = updates.filter { candidate -> (candidate.obj?.message?.id ?: 0L) >= offset }
        val newTs = (matched.maxOfOrNull { it.obj?.message?.id ?: 0L }?.plus(1) ?: offset).toString()
        return VkLongPollResponse(ts = newTs, updates = matched)
    }

    override suspend fun sendMessage(groupToken: String, peerId: Long, text: String) {
        if (text in failedSendTexts) {
            error("Simulated VK send failure.")
        }
        sentMessages += SentMessage(peerId, text)
    }

    override suspend fun setActivity(groupToken: String, peerId: Long, groupId: Long, type: String) {
        typingActivity += peerId
    }
}

private class PausedVkPoll {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val response = AtomicReference(VkLongPollResponse(ts = "1", updates = emptyList()))

    fun respondWith(update: VkLongPollUpdate, newTs: String) {
        response.set(VkLongPollResponse(ts = newTs, updates = listOf(update)))
    }

    fun response(): VkLongPollResponse = response.get()
}
