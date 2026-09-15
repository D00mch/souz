package ru.souz.backend.vk

import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.storage.postgres.PostgresChatRepository
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresVkBotBindingRepository
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import kotlin.time.Duration.Companion.milliseconds

class VkBotPollingServiceTest {
    @Test
    fun `long VK turn renews lease and lost lease fences reply and checkpoint`() = runBlocking {
        val schema = newPostgresSchema("vk_polling_renewal")
        val dataSource = PostgresDataSourceFactory.create(
            postgresAppConfig(schema, vkTokenEncryptionKey = TEST_VK_KEY).postgres
        )
        dataSource.use { source ->
            val userId = UUID.randomUUID().toString()
            val chatId = UUID.randomUUID()
            val tokenCrypto = VkBotTokenCrypto(TEST_VK_KEY)
            val bindingRepository = PostgresVkBotBindingRepository(source)
            PostgresUserRepository(source).ensureUser(userId)
            PostgresChatRepository(source).create(
                Chat(
                    id = chatId,
                    userId = userId,
                    title = "VK lease",
                    archived = false,
                    createdAt = Instant.parse("2026-08-24T00:00:00Z"),
                    updatedAt = Instant.parse("2026-08-24T00:00:00Z"),
                )
            )
            val binding = bindingRepository.upsertForChat(
                userId = userId,
                chatId = chatId,
                groupToken = tokenCrypto.encrypt("vk1:renewal-token"),
                groupTokenHash = sha256("vk1:renewal-token"),
                linkSecretHash = sha256("link-secret"),
                vkGroupId = 555L,
                vkGroupName = "Souz E2E",
                now = Instant.parse("2026-08-24T00:00:01Z"),
            )
            val linked = bindingRepository.claimVkUser(
                id = binding.id,
                linkSecretHash = sha256("link-secret"),
                vkUserId = 701L,
                vkPeerId = 701L,
                vkFirstName = "Linked",
                vkLastName = null,
                linkedAt = Instant.parse("2026-08-24T00:00:02Z"),
            )
            assertTrue(linked is VkUserClaimResult.Claimed)

            val turnStarted = CompletableDeferred<Unit>()
            val releaseTurn = CompletableDeferred<Unit>()
            val api = LeaseVkApi(update(id = 30, senderId = 701L, text = "long lease turn"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val service = VkBotPollingService(
                repository = bindingRepository,
                botApi = api,
                turnExecutor = { id, targetChatId, content, _, _: UserSettingsOverrides ->
                    assertEquals(userId, id)
                    assertEquals(chatId, targetChatId)
                    assertEquals("long lease turn", content)
                    turnStarted.complete(Unit)
                    releaseTurn.await()
                    completedTurn(userId, chatId, content)
                },
                tokenCrypto = tokenCrypto,
                scope = scope,
                instanceId = "renewal-test-instance",
                pollLoopDelayMs = 10L,
                leaseTtlSeconds = 3L,
                maxConcurrency = 1,
            )

            try {
                val poll = scope.async { service.pollEnabledOnce() }
                withTimeout(5.seconds) { turnStarted.await() }
                val initialLease = assertNotNull(bindingRepository.getByChat(chatId)?.pollerLeaseUntil)
                var renewedLease: Instant? = null
                withTimeout(5.seconds) {
                    while (renewedLease == null) {
                        val candidate = bindingRepository.getByChat(chatId)?.pollerLeaseUntil
                        if (candidate != null && candidate.isAfter(initialLease)) {
                            renewedLease = candidate
                        }
                        delay(25.milliseconds)
                    }
                }
                assertTrue(assertNotNull(renewedLease).isAfter(initialLease))

                source.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        update vk_bot_bindings
                        set poller_owner = 'stolen-instance',
                            poller_lease_until = current_timestamp + interval '1 minute'
                        where id = ?
                        """.trimIndent()
                    ).use { statement ->
                        statement.setObject(1, binding.id)
                        assertEquals(1, statement.executeUpdate())
                    }
                }
                releaseTurn.complete(Unit)
                withTimeout(5.seconds) { poll.join() }

                assertTrue(api.sentMessages.isEmpty())
                assertNull(assertNotNull(bindingRepository.getByChat(chatId)).lastTs)
            } finally {
                scope.cancel()
            }
        }
    }

    private fun completedTurn(userId: String, chatId: UUID, content: String): SendMessageResult =
        SendMessageResult(
            userMessage = ChatMessage(
                id = UUID.randomUUID(),
                userId = userId,
                chatId = chatId,
                seq = 1,
                role = ChatRole.USER,
                content = content,
                metadata = emptyMap(),
                createdAt = Instant.parse("2026-08-24T00:00:03Z"),
            ),
            assistantMessage = null,
            execution = AgentExecution(
                id = UUID.randomUUID(),
                userId = userId,
                chatId = chatId,
                userMessageId = null,
                assistantMessageId = null,
                status = AgentExecutionStatus.COMPLETED,
                requestId = null,
                clientMessageId = null,
                model = null,
                provider = null,
                startedAt = Instant.parse("2026-08-24T00:00:03Z"),
                finishedAt = Instant.parse("2026-08-24T00:00:04Z"),
                cancelRequested = false,
                errorCode = null,
                errorMessage = null,
                usage = null,
                metadata = emptyMap(),
            ),
        )
}

private class LeaseVkApi(private val update: VkLongPollUpdate) : VkBotApi {
    val sentMessages = CopyOnWriteArrayList<String>()

    override suspend fun getGroupInfo(groupToken: String): VkResponse<List<VkGroup>> =
        VkResponse(response = listOf(VkGroup(id = 555L, name = "Souz E2E")))

    override suspend fun getLongPollServer(groupToken: String, groupId: Long): VkResponse<VkLongPollServer> =
        VkResponse(response = VkLongPollServer(key = "key-1", server = "https://example.test/lp", ts = "1"))

    override suspend fun getUserInfo(groupToken: String, userId: Long): VkResponse<List<VkUser>> =
        VkResponse(response = emptyList())

    override suspend fun pollLongPoll(server: String, key: String, ts: String, waitSeconds: Int): VkLongPollResponse =
        VkLongPollResponse(ts = "2", updates = listOf(update))

    override suspend fun sendMessage(groupToken: String, peerId: Long, text: String) {
        sentMessages += text
    }

    override suspend fun setActivity(groupToken: String, peerId: Long, groupId: Long, type: String) = Unit
}

private fun update(id: Long, senderId: Long, text: String): VkLongPollUpdate =
    VkLongPollUpdate(
        type = "message_new",
        obj = VkMessageObjectWrapper(
            message = VkMessage(id = id, fromId = senderId, peerId = senderId, text = text),
        ),
    )

private const val TEST_VK_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
