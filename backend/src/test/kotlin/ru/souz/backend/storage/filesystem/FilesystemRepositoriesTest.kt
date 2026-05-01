package ru.souz.backend.storage.filesystem

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.choices.model.Choice
import ru.souz.backend.choices.model.ChoiceAnswer
import ru.souz.backend.choices.model.ChoiceKind
import ru.souz.backend.choices.model.ChoiceOption
import ru.souz.backend.choices.model.ChoiceStatus
import ru.souz.backend.choices.repository.ChoiceAnswerUpdateResult
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.settings.model.ToolPermission
import ru.souz.backend.settings.model.ToolPermissionMode
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.backend.settings.model.UserSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider

class FilesystemRepositoriesTest {
    @Test
    fun `repositories restore product and runtime state after restart and continue sequences`() = runTest {
        val dataDir = Files.createTempDirectory("filesystem-repositories")
        val userId = "opaque/user:42@example.com"
        val chat = chat(userId = userId, updatedAt = Instant.parse("2026-05-01T09:00:00Z"))

        val chatRepository = FilesystemChatRepository(dataDir)
        val messageRepository = FilesystemMessageRepository(dataDir)
        val stateRepository = FilesystemAgentStateRepository(dataDir)
        val executionRepository = FilesystemAgentExecutionRepository(dataDir)
        val choiceRepository = FilesystemChoiceRepository(dataDir)
        val eventRepository = FilesystemAgentEventRepository(dataDir)
        val settingsRepository = FilesystemUserSettingsRepository(dataDir)
        val providerKeyRepository = FilesystemUserProviderKeyRepository(
            dataDir = dataDir,
            masterKey = "test-master-key",
        )

        chatRepository.create(chat)
        val firstMessage = messageRepository.append(
            userId = userId,
            chatId = chat.id,
            role = ChatRole.USER,
            content = "hello",
        )
        val assistantPlaceholder = messageRepository.append(
            userId = userId,
            chatId = chat.id,
            role = ChatRole.ASSISTANT,
            content = "",
            metadata = mapOf("kind" to "placeholder"),
        )
        val settings = userSettings(userId = userId)
        settingsRepository.save(settings)
        val providerKey = UserProviderKey(
            userId = userId,
            provider = LlmProvider.OPENAI,
            encryptedApiKey = "enc-openai-user-a",
            keyHint = "...4321",
        )
        providerKeyRepository.save(providerKey)
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
        stateRepository.save(state)
        val execution = execution(
            userId = userId,
            chatId = chat.id,
            assistantMessageId = assistantPlaceholder.id,
            status = AgentExecutionStatus.WAITING_CHOICE,
            startedAt = Instant.parse("2026-05-01T09:10:00Z"),
        )
        executionRepository.create(execution)
        val choice = choice(userId = userId, chatId = chat.id, executionId = execution.id)
        choiceRepository.save(choice)
        val firstEvent = eventRepository.append(
            userId = userId,
            chatId = chat.id,
            executionId = execution.id,
            type = AgentEventType.EXECUTION_STARTED,
            payload = mapOf("requestId" to "req-1"),
        )
        val secondEvent = eventRepository.append(
            userId = userId,
            chatId = chat.id,
            executionId = execution.id,
            type = AgentEventType.CHOICE_REQUESTED,
            payload = mapOf("choiceId" to choice.id.toString()),
        )

        val userDirectories = listDirectories(dataDir.resolve("users"))
        val chatDir = userDirectories.single().resolve("chats").resolve(chat.id.toString())
        assertEquals(1, userDirectories.size)
        assertNotEquals(userId, userDirectories.single().name)
        assertTrue(Files.exists(dataDir.resolve("users").resolve(userId)).not())
        assertTrue(Files.exists(dataDir.resolve("users").resolve(userDirectories.single().name).resolve("settings.json")))
        assertTrue(Files.exists(dataDir.resolve("users").resolve(userDirectories.single().name).resolve("provider-keys.json")))
        assertTrue(Files.exists(chatDir.resolve("chat.json")))
        assertTrue(Files.exists(chatDir.resolve("messages.jsonl")))
        assertTrue(Files.exists(chatDir.resolve("agent-state.json")))
        assertTrue(Files.exists(chatDir.resolve("executions.jsonl")))
        assertTrue(Files.exists(chatDir.resolve("choices.jsonl")))
        assertTrue(Files.exists(chatDir.resolve("events.jsonl")))

        val reloadedChatRepository = FilesystemChatRepository(dataDir)
        val reloadedMessageRepository = FilesystemMessageRepository(dataDir)
        val reloadedStateRepository = FilesystemAgentStateRepository(dataDir)
        val reloadedExecutionRepository = FilesystemAgentExecutionRepository(dataDir)
        val reloadedChoiceRepository = FilesystemChoiceRepository(dataDir)
        val reloadedEventRepository = FilesystemAgentEventRepository(dataDir)
        val reloadedSettingsRepository = FilesystemUserSettingsRepository(dataDir)
        val reloadedProviderKeyRepository = FilesystemUserProviderKeyRepository(
            dataDir = dataDir,
            masterKey = "test-master-key",
        )

        assertEquals(chat, reloadedChatRepository.get(userId, chat.id))
        assertEquals(listOf(chat), reloadedChatRepository.list(userId))
        assertEquals(settings, reloadedSettingsRepository.get(userId))
        assertEquals(listOf(providerKey), reloadedProviderKeyRepository.list(userId))
        assertEquals(state, reloadedStateRepository.get(userId, chat.id))
        assertEquals(listOf(firstMessage, assistantPlaceholder), reloadedMessageRepository.list(userId, chat.id))
        assertEquals(assistantPlaceholder, reloadedMessageRepository.getById(userId, chat.id, assistantPlaceholder.id))
        assertEquals(execution, reloadedExecutionRepository.get(userId, execution.id))
        assertEquals(execution, reloadedExecutionRepository.findActive(userId, chat.id))
        assertEquals(listOf(choice), reloadedChoiceRepository.listByExecution(userId, chat.id, execution.id))
        assertEquals(listOf(firstEvent, secondEvent), reloadedEventRepository.listByChat(userId, chat.id))

        val updatedAssistant = reloadedMessageRepository.updateContent(
            userId = userId,
            chatId = chat.id,
            messageId = assistantPlaceholder.id,
            content = "assistant reply",
        )
        val nextUserMessage = reloadedMessageRepository.append(
            userId = userId,
            chatId = chat.id,
            role = ChatRole.USER,
            content = "follow up",
        )
        val answerResult = reloadedChoiceRepository.answerPending(
            userId = userId,
            choiceId = choice.id,
            answer = ChoiceAnswer(
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
        reloadedExecutionRepository.update(completedExecution)
        val thirdEvent = reloadedEventRepository.append(
            userId = userId,
            chatId = chat.id,
            executionId = execution.id,
            type = AgentEventType.EXECUTION_FINISHED,
            payload = mapOf("status" to "completed"),
        )

        assertEquals(assistantPlaceholder.copy(content = "assistant reply"), updatedAssistant)
        assertEquals(3L, nextUserMessage.seq)
        assertIs<ChoiceAnswerUpdateResult.Updated>(answerResult)
        assertEquals(3L, thirdEvent.seq)

        val restartedMessageRepository = FilesystemMessageRepository(dataDir)
        val restartedStateRepository = FilesystemAgentStateRepository(dataDir)
        val restartedExecutionRepository = FilesystemAgentExecutionRepository(dataDir)
        val restartedChoiceRepository = FilesystemChoiceRepository(dataDir)
        val restartedEventRepository = FilesystemAgentEventRepository(dataDir)

        assertEquals(state, restartedStateRepository.get(userId, chat.id))
        assertEquals(
            listOf(
                firstMessage,
                assistantPlaceholder.copy(content = "assistant reply"),
                nextUserMessage,
            ),
            restartedMessageRepository.list(userId, chat.id),
        )
        assertEquals(
            assistantPlaceholder.copy(content = "assistant reply"),
            restartedMessageRepository.getById(userId, chat.id, assistantPlaceholder.id),
        )
        assertEquals(completedExecution, restartedExecutionRepository.getByChat(userId, chat.id, execution.id))
        assertNull(restartedExecutionRepository.findActive(userId, chat.id))
        assertEquals(
            listOf(choice.copy(
                status = ChoiceStatus.ANSWERED,
                answer = ChoiceAnswer(
                    selectedOptionIds = setOf("a"),
                    freeText = "because alpha",
                    metadata = mapOf("source" to "web-ui"),
                ),
                answeredAt = Instant.parse("2026-05-01T09:15:00Z"),
            )),
            restartedChoiceRepository.listByExecution(userId, chat.id, execution.id),
        )
        assertEquals(
            listOf(firstEvent, secondEvent, thirdEvent),
            restartedEventRepository.listByChat(userId, chat.id),
        )
        assertEquals(
            ChoiceAnswerUpdateResult.NotPending(
                choice.copy(
                    status = ChoiceStatus.ANSWERED,
                    answer = ChoiceAnswer(
                        selectedOptionIds = setOf("a"),
                        freeText = "because alpha",
                        metadata = mapOf("source" to "web-ui"),
                    ),
                    answeredAt = Instant.parse("2026-05-01T09:15:00Z"),
                )
            ),
            restartedChoiceRepository.answerPending(
                userId = userId,
                choiceId = choice.id,
                answer = ChoiceAnswer(selectedOptionIds = setOf("a")),
                answeredAt = Instant.parse("2026-05-01T09:20:00Z"),
            ),
        )
    }

    @Test
    fun `corrupted agent state does not break visible product history`() = runTest {
        val dataDir = Files.createTempDirectory("filesystem-agent-state-corruption")
        val userId = "opaque/user:broken-state"
        val chatId = UUID.randomUUID()
        val messageRepository = FilesystemMessageRepository(dataDir)
        val stateRepository = FilesystemAgentStateRepository(dataDir)

        val firstMessage = messageRepository.append(
            userId = userId,
            chatId = chatId,
            role = ChatRole.USER,
            content = "visible history",
        )
        stateRepository.save(
            agentState(
                userId = userId,
                chatId = chatId,
                basedOnMessageSeq = firstMessage.seq,
                history = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.system,
                        content = "runtime-only state",
                    )
                ),
            )
        )

        val agentStateFile = findSingleFile(dataDir, "agent-state.json")
        Files.writeString(agentStateFile, "{ definitely-not-json")

        val reloadedStateRepository = FilesystemAgentStateRepository(dataDir)
        val reloadedMessageRepository = FilesystemMessageRepository(dataDir)
        val secondMessage = reloadedMessageRepository.append(
            userId = userId,
            chatId = chatId,
            role = ChatRole.ASSISTANT,
            content = "still readable",
        )

        assertNull(reloadedStateRepository.get(userId, chatId))
        assertEquals(2L, secondMessage.seq)
        assertEquals(
            listOf("visible history", "still readable"),
            reloadedMessageRepository.list(userId, chatId).map { it.content },
        )
    }

    @Test
    fun `legacy session repository round trips through filesystem agent state repository`() = runTest {
        val dataDir = Files.createTempDirectory("filesystem-agent-session")
        val repository = AgentStateBackedSessionRepository(FilesystemAgentStateRepository(dataDir))
        val key = AgentConversationKey(
            userId = "opaque/user:session",
            conversationId = UUID.randomUUID().toString(),
        )
        val session = AgentConversationSession(
            activeAgentId = AgentId.LUA_GRAPH,
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
        )

        repository.save(key, session)

        val reloadedRepository = AgentStateBackedSessionRepository(FilesystemAgentStateRepository(dataDir))
        val storedState = FilesystemAgentStateRepository(dataDir).get(key.userId, UUID.fromString(key.conversationId))

        assertEquals(session, reloadedRepository.load(key))
        assertNotNull(storedState)
        assertEquals(AgentId.LUA_GRAPH, storedState.activeAgentId)
        assertEquals(session.history, storedState.history)
        assertEquals(Locale.forLanguageTag("en-US"), storedState.locale)
        assertEquals(ZoneId.of("Europe/Amsterdam"), storedState.timeZone)
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

    private fun choice(
        userId: String,
        chatId: UUID,
        executionId: UUID,
    ): Choice =
        Choice(
            id = UUID.randomUUID(),
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            kind = ChoiceKind.GENERIC_SELECTION,
            title = "Pick one",
            selectionMode = "single",
            options = listOf(ChoiceOption(id = "a", label = "A", content = "alpha")),
            payload = mapOf("origin" to "test"),
            status = ChoiceStatus.PENDING,
            answer = null,
            createdAt = Instant.parse("2026-05-01T08:30:00Z"),
            expiresAt = null,
            answeredAt = null,
        )

    private fun listDirectories(root: Path): List<Path> =
        Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) }.toList()
        }

    private fun findSingleFile(root: Path, fileName: String): Path =
        Files.walk(root).use { stream ->
            stream.filter { it.fileName.toString() == fileName }.findFirst().orElseThrow()
        }
}
