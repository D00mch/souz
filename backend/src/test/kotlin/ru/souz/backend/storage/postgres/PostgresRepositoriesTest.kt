package ru.souz.backend.storage.postgres

import java.lang.reflect.Proxy
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Disabled
import ru.souz.agent.AgentId
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.agent.session.AgentStateConflictException
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionAnswer
import ru.souz.backend.options.model.OptionKind
import ru.souz.backend.options.model.OptionItem
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.options.repository.OptionAnswerUpdateResult
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.settings.model.ToolPermission
import ru.souz.backend.settings.model.ToolPermissionMode
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.testutil.rawEventPayload
import ru.souz.backend.user.model.UserRecord
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider

@Disabled
class PostgresRepositoriesTest {
    @Test
    fun `fresh schema bootstrap applies postgres migrations in unique order and creates tool_calls`() {
        val schema = newPostgresSchema("postgres_fresh_bootstrap")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)

        dataSource.use {
            assertEquals(
                listOf("1", "2", "3", "4"),
                appliedMigrationVersions(it),
            )
            assertTrue(tableExists(it, "tool_calls"))
        }
    }

    @Test
    fun `user settings repository tolerates legacy partial settings json`() = runTest {
        val schema = newPostgresSchema("postgres_legacy_settings_json")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)
        val userRepository = PostgresUserRepository(dataSource)
        val settingsRepository = PostgresUserSettingsRepository(dataSource)

        dataSource.use {
            userRepository.ensureUser("legacy-user")
            dataSource.write { connection ->
                connection.prepareStatement(
                    """
                    insert into user_settings(user_id, settings_json, created_at, updated_at)
                    values (?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, "legacy-user")
                    statement.setJson(
                        2,
                        """
                        {
                          "defaultModel": "${LLMModel.Max.alias}",
                          "contextSize": 12000,
                          "temperature": 0.2,
                          "locale": "en-US",
                          "timeZone": "Europe/Amsterdam"
                        }
                        """.trimIndent(),
                    )
                    statement.setInstant(3, Instant.parse("2026-05-01T09:00:00Z"))
                    statement.setInstant(4, Instant.parse("2026-05-01T09:00:00Z"))
                    statement.executeUpdate()
                }
            }

            val stored = settingsRepository.get("legacy-user")

            assertNotNull(stored)
            assertEquals("legacy-user", stored.userId)
            assertEquals(LLMModel.Max, stored.defaultModel)
            assertEquals(12_000, stored.contextSize)
            assertEquals(0.2f, stored.temperature)
            assertEquals(Locale.forLanguageTag("en-US"), stored.locale)
            assertEquals(ZoneId.of("Europe/Amsterdam"), stored.timeZone)
            assertNull(stored.systemPrompt)
            assertNull(stored.enabledTools)
            assertNull(stored.showToolEvents)
            assertNull(stored.streamingMessages)
            assertTrue(stored.toolPermissions.isEmpty())
            assertTrue(stored.mcp.isEmpty())
        }
    }

    @Test
    fun `user settings repository saves and restores explicit created and updated timestamps`() = runTest {
        val schema = newPostgresSchema("postgres_user_settings_timestamps")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)
        val userRepository = PostgresUserRepository(dataSource)
        val settingsRepository = PostgresUserSettingsRepository(dataSource)
        val settings = UserSettings(
            userId = "user-a",
            defaultModel = LLMModel.Max,
            contextSize = 16_000,
            temperature = 0.7f,
            locale = Locale.forLanguageTag("en-US"),
            timeZone = ZoneId.of("UTC"),
            systemPrompt = "system-user-a",
            enabledTools = setOf("ListFiles"),
            showToolEvents = true,
            streamingMessages = true,
            toolPermissions = mapOf("ListFiles" to ToolPermission(ToolPermissionMode.ALLOW)),
            mcp = mapOf("repo" to UserMcpServer(enabled = true)),
            createdAt = Instant.parse("2026-05-01T09:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T09:05:00Z"),
        )

        dataSource.use {
            userRepository.ensureUser(settings.userId)

            val saved = settingsRepository.save(settings)
            val stored = settingsRepository.get(settings.userId)

            assertEquals(settings, saved)
            assertEquals(settings, stored)
            assertEquals(settings.createdAt, stored?.createdAt)
            assertEquals(settings.updatedAt, stored?.updatedAt)
        }
    }

    @Test
    fun `setInstant binds non null instants as utc timestamptz`() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val statement = Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
        ) { _, method, args ->
            when (method.name) {
                "setObject", "setNull" -> calls += method.name to args.orEmpty().toList()
            }
            null
        } as PreparedStatement
        val instant = Instant.parse("2026-05-01T09:00:00Z")

        statement.setInstant(1, instant)

        assertEquals(
            listOf(
                "setObject" to listOf<Any?>(
                    1,
                    OffsetDateTime.ofInstant(instant, ZoneOffset.UTC),
                    Types.TIMESTAMP_WITH_TIMEZONE,
                )
            ),
            calls,
        )
    }

    @Test
    fun `provider key repository ignores invalid provider rows`() = runTest {
        val schema = newPostgresSchema("postgres_invalid_provider_rows")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)
        val userRepository = PostgresUserRepository(dataSource)
        val repository = PostgresUserProviderKeyRepository(dataSource)

        dataSource.use {
            userRepository.ensureUser("user-a")
            dataSource.write { connection ->
                connection.prepareStatement(
                    """
                    insert into user_provider_keys(
                        user_id,
                        provider,
                        encrypted_api_key,
                        key_hint,
                        created_at,
                        updated_at
                    )
                    values (?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, "user-a")
                    statement.setString(2, "OPENAI")
                    statement.setBytes(3, "enc-openai".toByteArray())
                    statement.setString(4, "...1234")
                    statement.setInstant(5, Instant.parse("2026-05-01T09:00:00Z"))
                    statement.setInstant(6, Instant.parse("2026-05-01T09:00:00Z"))
                    statement.executeUpdate()

                    statement.setString(1, "user-a")
                    statement.setString(2, "BROKEN_PROVIDER")
                    statement.setBytes(3, "enc-broken".toByteArray())
                    statement.setString(4, "...9999")
                    statement.setInstant(5, Instant.parse("2026-05-01T09:01:00Z"))
                    statement.setInstant(6, Instant.parse("2026-05-01T09:01:00Z"))
                    statement.executeUpdate()
                }
            }

            val keys = repository.list("user-a")

            assertEquals(1, keys.size)
            assertEquals(LlmProvider.OPENAI, keys.single().provider)
            assertEquals("...1234", keys.single().keyHint)
        }
    }

    @Test
    fun `user repository upserts single user row and throttles last seen updates`() = runTest {
        val schema = newPostgresSchema("postgres_users")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)
        val repository = PostgresUserRepository(dataSource)

        dataSource.use {
            val first = repository.ensureUser("user-a")
            val second = repository.ensureUser("user-a")

            assertEquals("user-a", first.id)
            assertEquals(first.createdAt, second.createdAt)
            assertEquals(first.lastSeenAt, second.lastSeenAt)
            assertEquals(1, userCount(it))
        }
    }

    @Test
    fun `tool call repository round trips lifecycle rows`() = runTest {
        val schema = newPostgresSchema("postgres_tool_calls")

        postgresRepositories(schema).use { repositories ->
            val chat = chat(userId = "user-tools", updatedAt = Instant.parse("2026-05-01T10:00:00Z"))
            repositories.userRepository.ensureUser(chat.userId)
            repositories.chatRepository.create(chat)
            val execution = execution(
                userId = chat.userId,
                chatId = chat.id,
                assistantMessageId = null,
                status = AgentExecutionStatus.RUNNING,
                startedAt = Instant.parse("2026-05-01T10:01:00Z"),
            )
            repositories.executionRepository.create(execution)

            repositories.toolCallRepository.started(
                context = ToolCallContext(
                    userId = chat.userId,
                    chatId = chat.id.toString(),
                    executionId = execution.id.toString(),
                    toolCallId = "tool-1",
                ),
                name = "OpenBrowser",
                argumentsPreview = """{"url":"https://example.com"}""",
                startedAt = Instant.parse("2026-05-01T10:01:01Z"),
            )
            repositories.toolCallRepository.failed(
                context = ToolCallContext(
                    userId = chat.userId,
                    chatId = chat.id.toString(),
                    executionId = execution.id.toString(),
                    toolCallId = "tool-1",
                ),
                name = "OpenBrowser",
                error = "IllegalStateException: [REDACTED]",
                finishedAt = Instant.parse("2026-05-01T10:01:02Z"),
                durationMs = 1_000,
            )

            val stored = repositories.toolCallRepository.get(
                ToolCallContext(
                    userId = chat.userId,
                    chatId = chat.id.toString(),
                    executionId = execution.id.toString(),
                    toolCallId = "tool-1",
                )
            )

            assertNotNull(stored)
            assertEquals(ToolCallStatus.FAILED, stored.status)
            assertEquals("OpenBrowser", stored.name)
            assertEquals(1_000L, stored.durationMs)
        }
    }

    @Test
    fun `chat repository updates title and archived fields`() = runTest {
        val schema = newPostgresSchema("postgres_chat_updates")
        val userId = "opaque/user:42@example.com"
        val chat = chat(
            userId = userId,
            updatedAt = Instant.parse("2026-05-01T09:00:00Z"),
        ).copy(
            title = "Original",
            archived = false,
        )

        postgresRepositories(schema).use { repositories ->
            repositories.userRepository.ensureUser(userId)
            repositories.chatRepository.create(chat)

            val renamed = repositories.chatRepository.updateTitle(
                userId = userId,
                chatId = chat.id,
                title = "Renamed",
            )
            assertEquals("Renamed", renamed?.title)
            assertTrue(renamed!!.updatedAt.isAfter(chat.updatedAt))

            val archived = repositories.chatRepository.updateArchived(
                userId = userId,
                chatId = chat.id,
                archived = true,
            )
            assertEquals(true, archived?.archived)
            assertTrue(archived!!.updatedAt.isAfter(renamed.updatedAt))
            assertEquals(archived, repositories.chatRepository.get(userId, chat.id))
            assertNull(repositories.chatRepository.updateTitle("user-b", chat.id, "Foreign"))
            assertNull(repositories.chatRepository.updateArchived("user-b", chat.id, archived = false))
        }
    }

    @Test
    fun `repositories restore product and runtime state after restart and continue sequences`() = runTest {
        val schema = newPostgresSchema("postgres_repositories_roundtrip")
        val userId = "opaque/user:42@example.com"
        val chat = chat(userId = userId, updatedAt = Instant.parse("2026-05-01T09:00:00Z"))

        postgresRepositories(schema, durableEvents = true).use { repositories ->
            repositories.userRepository.ensureUser(userId)
            repositories.chatRepository.create(chat)
            val firstMessage = repositories.messageRepository.append(
                userId = userId,
                chatId = chat.id,
                role = ChatRole.USER,
                content = "hello",
            )
            val assistantPlaceholder = repositories.messageRepository.append(
                userId = userId,
                chatId = chat.id,
                role = ChatRole.ASSISTANT,
                content = "",
                metadata = mapOf("kind" to "placeholder"),
            )
            val settings = userSettings(userId = userId)
            repositories.settingsRepository.save(settings)
            val providerKey = UserProviderKey(
                userId = userId,
                provider = LlmProvider.OPENAI,
                encryptedApiKey = "enc-openai-user-a",
                keyHint = "...6789",
            )
            repositories.providerKeyRepository.save(providerKey)
            val state = agentState(
                userId = userId,
                chatId = chat.id,
                basedOnMessageSeq = assistantPlaceholder.seq,
                history = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.system,
                        content = "system-state",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = "hello",
                        attachments = listOf("draft.md"),
                        name = "operator",
                    ),
                ),
            )
            repositories.stateRepository.save(state)
            val execution = execution(
                userId = userId,
                chatId = chat.id,
                assistantMessageId = assistantPlaceholder.id,
                status = AgentExecutionStatus.WAITING_OPTION,
                startedAt = Instant.parse("2026-05-01T09:10:00Z"),
            )
            repositories.executionRepository.create(execution)
            val option = option(userId = userId, chatId = chat.id, executionId = execution.id)
            repositories.optionRepository.save(option)
            val firstEvent = repositories.eventRepository.append(
                userId = userId,
                chatId = chat.id,
                executionId = execution.id,
                type = AgentEventType.EXECUTION_STARTED,
                payload = rawEventPayload("requestId" to "req-1"),
            )
            val secondEvent = repositories.eventRepository.append(
                userId = userId,
                chatId = chat.id,
                executionId = execution.id,
                type = AgentEventType.OPTION_REQUESTED,
                payload = rawEventPayload("optionId" to option.id.toString()),
            )

            assertEquals(chat, repositories.chatRepository.get(userId, chat.id))
            assertEquals(listOf(chat), repositories.chatRepository.list(userId))
            assertEquals(settings, repositories.settingsRepository.get(userId))
            assertEquals(listOf(providerKey), repositories.providerKeyRepository.list(userId))
            assertEquals(state, repositories.stateRepository.get(userId, chat.id))
            assertEquals(listOf(firstMessage, assistantPlaceholder), repositories.messageRepository.list(userId, chat.id))
            assertEquals(assistantPlaceholder, repositories.messageRepository.getById(userId, chat.id, assistantPlaceholder.id))
            assertEquals(execution, repositories.executionRepository.get(userId, execution.id))
            assertEquals(execution, repositories.executionRepository.findActive(userId, chat.id))
            assertEquals(listOf(option), repositories.optionRepository.listByExecution(userId, chat.id, execution.id))
            assertEquals(listOf(firstEvent, secondEvent), repositories.eventRepository.listByChat(userId, chat.id))
        }

        postgresRepositories(schema, durableEvents = true).use { repositories ->
            val assistantPlaceholder = repositories.messageRepository.list(userId, chat.id).single { it.role == ChatRole.ASSISTANT }
            val execution = repositories.executionRepository.findActive(userId, chat.id)
            val option = repositories.optionRepository.listByExecution(userId, chat.id, execution!!.id).single()

            val updatedAssistant = repositories.messageRepository.updateContent(
                userId = userId,
                chatId = chat.id,
                messageId = assistantPlaceholder.id,
                content = "assistant reply",
            )
            val nextUserMessage = repositories.messageRepository.append(
                userId = userId,
                chatId = chat.id,
                role = ChatRole.USER,
                content = "follow up",
            )
            val answerResult = repositories.optionRepository.answerPending(
                userId = userId,
                optionId = option.id,
                answer = OptionAnswer(
                    selectedOptionIds = setOf("a"),
                    freeText = "because alpha",
                    metadata = mapOf("source" to "web-ui"),
                ),
                answeredAt = Instant.parse("2026-05-01T09:15:00Z"),
            )
            val completedExecution = execution.copy(
                status = AgentExecutionStatus.COMPLETED,
                finishedAt = Instant.parse("2026-05-01T09:16:00Z"),
                usage = AgentExecutionUsage(
                    promptTokens = 10,
                    completionTokens = 5,
                    totalTokens = 15,
                    precachedTokens = 1,
                ),
                metadata = execution.metadata + ("assistantMessageId" to assistantPlaceholder.id.toString()),
            )
            repositories.executionRepository.update(completedExecution)
            val thirdEvent = repositories.eventRepository.append(
                userId = userId,
                chatId = chat.id,
                executionId = execution.id,
                type = AgentEventType.EXECUTION_FINISHED,
                payload = rawEventPayload("status" to "completed"),
            )

            assertEquals(assistantPlaceholder.copy(content = "assistant reply"), updatedAssistant)
            assertEquals(3L, nextUserMessage.seq)
            assertIs<OptionAnswerUpdateResult.Updated>(answerResult)
            assertEquals(3L, thirdEvent.seq)
        }

        postgresRepositories(schema, durableEvents = true).use { repositories ->
            val storedState = repositories.stateRepository.get(userId, chat.id)
            val execution = repositories.executionRepository.listByChat(userId, chat.id).single()
            val option = repositories.optionRepository.listByExecution(userId, chat.id, execution.id).single()

            assertNotNull(storedState)
            assertEquals(
                listOf("hello", "assistant reply", "follow up"),
                repositories.messageRepository.list(userId, chat.id).map { it.content },
            )
            assertEquals("assistant reply", repositories.messageRepository.get(userId, chat.id, 2L)?.content)
            assertEquals(AgentExecutionStatus.COMPLETED, execution.status)
            assertNull(repositories.executionRepository.findActive(userId, chat.id))
            assertEquals(OptionStatus.ANSWERED, option.status)
            assertEquals(
                listOf("execution.started", "option.requested", "execution.finished"),
                repositories.eventRepository.listByChat(userId, chat.id).map { it.type.value },
            )
        }
    }

    @Test
    fun `unique active execution index blocks second active execution`() = runTest {
        val schema = newPostgresSchema("postgres_active_execution")

        postgresRepositories(schema).use { repositories ->
            val chat = chat(userId = "user-active", updatedAt = Instant.parse("2026-05-01T10:00:00Z"))
            repositories.userRepository.ensureUser(chat.userId)
            repositories.chatRepository.create(chat)
            repositories.executionRepository.create(
                execution(
                    userId = chat.userId,
                    chatId = chat.id,
                    assistantMessageId = null,
                    status = AgentExecutionStatus.RUNNING,
                    startedAt = Instant.parse("2026-05-01T10:01:00Z"),
                )
            )

            assertFailsWith<ActiveAgentExecutionConflictException> {
                repositories.executionRepository.create(
                    execution(
                        userId = chat.userId,
                        chatId = chat.id,
                        assistantMessageId = null,
                        status = AgentExecutionStatus.QUEUED,
                        startedAt = Instant.parse("2026-05-01T10:02:00Z"),
                    )
                )
            }
        }
    }

    @Test
    fun `optimistic locking on agent state does not allow stale overwrite`() = runTest {
        val schema = newPostgresSchema("postgres_state_lock")

        postgresRepositories(schema).use { repositories ->
            val chat = chat(userId = "user-lock", updatedAt = Instant.parse("2026-05-01T11:00:00Z"))
            repositories.userRepository.ensureUser(chat.userId)
            repositories.chatRepository.create(chat)

            val inserted = repositories.stateRepository.save(
                agentState(
                    userId = chat.userId,
                    chatId = chat.id,
                    basedOnMessageSeq = 1L,
                    history = listOf(
                        LLMRequest.Message(
                            role = LLMMessageRole.user,
                            content = "state-v1",
                        )
                    ),
                ).copy(rowVersion = 0L)
            )
            val fresh = repositories.stateRepository.save(
                inserted.copy(
                    history = listOf(
                        LLMRequest.Message(
                            role = LLMMessageRole.assistant,
                            content = "state-v2",
                        )
                    ),
                    updatedAt = Instant.parse("2026-05-01T11:05:00Z"),
                )
            )

            val error = assertFailsWith<AgentStateConflictException> {
                repositories.stateRepository.save(
                    inserted.copy(
                        history = listOf(
                            LLMRequest.Message(
                                role = LLMMessageRole.system,
                                content = "stale-overwrite",
                            )
                        ),
                        updatedAt = Instant.parse("2026-05-01T11:10:00Z"),
                    )
                )
            }

            assertEquals(chat.userId, error.userId)
            assertEquals(chat.id, error.chatId)
            assertEquals(fresh, repositories.stateRepository.get(chat.userId, chat.id))
            assertEquals("state-v2", repositories.stateRepository.get(chat.userId, chat.id)?.history?.single()?.content)
        }
    }

    @Test
    fun `durable event replay from db survives restart when enabled`() = runTest {
        val schema = newPostgresSchema("postgres_durable_events")
        val userId = "user-events"
        val chat = chat(userId = userId, updatedAt = Instant.parse("2026-05-01T12:00:00Z"))
        val executionId = UUID.randomUUID()

        postgresRepositories(schema, durableEvents = true).use { repositories ->
            repositories.userRepository.ensureUser(userId)
            repositories.chatRepository.create(chat)
            repositories.eventRepository.append(
                userId = userId,
                chatId = chat.id,
                executionId = executionId,
                type = AgentEventType.EXECUTION_STARTED,
                payload = rawEventPayload("step" to "start"),
            )
            repositories.eventRepository.append(
                userId = userId,
                chatId = chat.id,
                executionId = executionId,
                type = AgentEventType.MESSAGE_COMPLETED,
                payload = rawEventPayload("step" to "done"),
            )
        }

        postgresRepositories(schema, durableEvents = true).use { repositories ->
            val service = AgentEventService(
                chatRepository = repositories.chatRepository,
                eventRepository = repositories.eventRepository,
                eventBus = AgentEventBus(),
            )

            val stream = service.openStream(userId = userId, chatId = chat.id, afterSeq = 1L)
            try {
                assertEquals(
                    listOf("message.completed"),
                    stream.replay.map { it.type.value },
                )
                assertEquals(2L, stream.replay.single().seq)
            } finally {
                stream.close()
            }
        }
    }

    @Test
    fun `agent session repository round trips through postgres agent state repository`() = runTest {
        val schema = newPostgresSchema("postgres_legacy_session")

        postgresRepositories(schema).use { repositories ->
            val chatId = UUID.randomUUID()
            repositories.userRepository.ensureUser("opaque/user:session")
            repositories.chatRepository.create(
                chat(
                    userId = "opaque/user:session",
                    updatedAt = Instant.parse("2026-05-01T13:00:00Z"),
                ).copy(id = chatId)
            )
            val repository = AgentStateBackedSessionRepository(repositories.stateRepository)
            val key = AgentConversationKey(
                userId = "opaque/user:session",
                conversationId = chatId.toString(),
            )
            val session = AgentConversationSession(
                activeAgentId = AgentId.GRAPH,
                history = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = "hello",
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = "world",
                    ),
                ),
                temperature = 0.25f,
                locale = "en-US",
                timeZone = "Europe/Amsterdam",
                basedOnMessageSeq = 2L,
                rowVersion = 0L,
            )

            repository.save(key, session)

            val storedState = repositories.stateRepository.get(key.userId, chatId)
            assertEquals(session, repository.load(key))
            assertNotNull(storedState)
            assertEquals(AgentId.GRAPH, storedState.activeAgentId)
            assertEquals(session.history, storedState.history)
            assertEquals(Locale.forLanguageTag("en-US"), storedState.locale)
            assertEquals(ZoneId.of("Europe/Amsterdam"), storedState.timeZone)
            assertEquals(2L, storedState.basedOnMessageSeq)
        }
    }

    private fun postgresRepositories(
        schema: String,
        durableEvents: Boolean = false,
    ): PostgresRepositoryBundle {
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema, durableEvents).postgres!!)
        return PostgresRepositoryBundle(
            dataSource = dataSource,
            userRepository = PostgresUserRepository(dataSource),
            chatRepository = PostgresChatRepository(dataSource),
            messageRepository = PostgresMessageRepository(dataSource),
            stateRepository = PostgresAgentStateRepository(dataSource),
            executionRepository = PostgresAgentExecutionRepository(dataSource),
            optionRepository = PostgresOptionRepository(dataSource),
            eventRepository = PostgresAgentEventRepository(dataSource),
            toolCallRepository = PostgresToolCallRepository(dataSource),
            settingsRepository = PostgresUserSettingsRepository(dataSource),
            providerKeyRepository = PostgresUserProviderKeyRepository(dataSource),
        )
    }

    private fun userSettings(
        userId: String,
        defaultModel: LLMModel = LLMModel.Max,
    ): UserSettings =
        UserSettings(
            userId = userId,
            defaultModel = defaultModel,
            contextSize = 16_000,
            temperature = 0.7f,
            locale = Locale.forLanguageTag("ru-RU"),
            timeZone = ZoneId.of("Europe/Moscow"),
            systemPrompt = "system-$userId",
            enabledTools = setOf("ListFiles"),
            showToolEvents = true,
            streamingMessages = true,
            toolPermissions = mapOf("ListFiles" to ToolPermission(ToolPermissionMode.ALLOW)),
            mcp = mapOf("repo" to UserMcpServer(enabled = true)),
            createdAt = Instant.parse("2026-05-01T08:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T08:05:00Z"),
        )

    private fun chat(
        userId: String,
        updatedAt: Instant,
    ): Chat =
        Chat(
            id = UUID.randomUUID(),
            userId = userId,
            title = "chat-$userId",
            archived = false,
            createdAt = Instant.parse("2026-05-01T07:00:00Z"),
            updatedAt = updatedAt,
        )

    private fun agentState(
        userId: String,
        chatId: UUID,
        basedOnMessageSeq: Long,
        history: List<LLMRequest.Message>,
    ): AgentConversationState =
        AgentConversationState(
            userId = userId,
            chatId = chatId,
            schemaVersion = 1,
            activeAgentId = AgentId.default,
            history = history,
            temperature = 0.3f,
            locale = Locale.forLanguageTag("ru-RU"),
            timeZone = ZoneId.of("Europe/Moscow"),
            basedOnMessageSeq = basedOnMessageSeq,
            updatedAt = Instant.parse("2026-05-01T08:15:00Z"),
            rowVersion = 7L,
        )

    private fun appliedMigrationVersions(dataSource: DataSource): List<String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select version
                from flyway_schema_history
                where success = true
                order by installed_rank
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.getString("version"))
                        }
                    }
                }
            }
        }

    private fun userCount(dataSource: DataSource): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("select count(*) from users").use { statement ->
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
            }
        }

    private fun tableExists(
        dataSource: DataSource,
        tableName: String,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select exists(
                  select 1
                  from information_schema.tables
                  where table_schema = current_schema()
                    and table_name = ?
                )
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, tableName)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getBoolean(1)
                }
            }
        }

    private fun execution(
        userId: String,
        chatId: UUID,
        assistantMessageId: UUID?,
        status: AgentExecutionStatus,
        startedAt: Instant,
    ): AgentExecution =
        AgentExecution(
            id = UUID.randomUUID(),
            userId = userId,
            chatId = chatId,
            userMessageId = UUID.randomUUID(),
            assistantMessageId = assistantMessageId,
            status = status,
            requestId = "req-$userId",
            clientMessageId = "client-$userId",
            model = LLMModel.Max,
            provider = LLMModel.Max.provider,
            startedAt = startedAt,
            finishedAt = null,
            cancelRequested = false,
            errorCode = null,
            errorMessage = null,
            usage = null,
            metadata = mapOf(
                "source" to "test",
                "assistantMessageId" to assistantMessageId.toString(),
            ),
        )

    private fun option(
        userId: String,
        chatId: UUID,
        executionId: UUID,
    ): Option =
        Option(
            id = UUID.randomUUID(),
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            kind = OptionKind.GENERIC_SELECTION,
            title = "Pick one",
            selectionMode = "single",
            options = listOf(OptionItem(id = "a", label = "A", content = "alpha")),
            payload = mapOf("origin" to "test"),
            status = OptionStatus.PENDING,
            answer = null,
            createdAt = Instant.parse("2026-05-01T08:30:00Z"),
            expiresAt = null,
            answeredAt = null,
        )
}

private data class PostgresRepositoryBundle(
    val dataSource: com.zaxxer.hikari.HikariDataSource,
    val userRepository: PostgresUserRepository,
    val chatRepository: PostgresChatRepository,
    val messageRepository: PostgresMessageRepository,
    val stateRepository: PostgresAgentStateRepository,
    val executionRepository: PostgresAgentExecutionRepository,
    val optionRepository: PostgresOptionRepository,
    val eventRepository: PostgresAgentEventRepository,
    val toolCallRepository: PostgresToolCallRepository,
    val settingsRepository: PostgresUserSettingsRepository,
    val providerKeyRepository: PostgresUserProviderKeyRepository,
) : AutoCloseable {
    override fun close() {
        dataSource.close()
    }
}
