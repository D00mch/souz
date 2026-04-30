package ru.souz.backend.storage.memory

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.backend.agent.session.AgentConversationState
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.choices.model.Choice
import ru.souz.backend.choices.model.ChoiceKind
import ru.souz.backend.choices.model.ChoiceOption
import ru.souz.backend.choices.model.ChoiceStatus
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.settings.model.ToolPermission
import ru.souz.backend.settings.model.ToolPermissionMode
import ru.souz.backend.settings.model.UserMcpServer
import ru.souz.backend.settings.model.UserSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest

class MemoryRepositoriesTest {
    @Test
    fun `user settings are stored per user`() = runTest {
        val repository = MemoryUserSettingsRepository()
        val userA = userSettings(
            userId = "user-a",
            defaultModel = LLMModel.Max,
            locale = Locale.forLanguageTag("ru-RU"),
        )
        val userB = userSettings(
            userId = "user-b",
            defaultModel = LLMModel.QwenMax,
            locale = Locale.forLanguageTag("en-US"),
        )

        repository.save(userA)
        repository.save(userB)

        assertEquals(userA, repository.get("user-a"))
        assertEquals(userB, repository.get("user-b"))
    }

    @Test
    fun `updating settings does not leak between users`() = runTest {
        val repository = MemoryUserSettingsRepository()

        repository.save(userSettings(userId = "user-a", temperature = 0.2f))
        repository.save(userSettings(userId = "user-b", temperature = 0.8f))
        repository.save(userSettings(userId = "user-a", temperature = 0.4f))

        assertEquals(0.4f, repository.get("user-a")?.temperature)
        assertEquals(0.8f, repository.get("user-b")?.temperature)
    }

    @Test
    fun `chats can be created read and listed per user`() = runTest {
        val repository = MemoryChatRepository()
        val older = chat(userId = "user-a", updatedAt = Instant.parse("2026-04-30T08:00:00Z"))
        val newer = chat(userId = "user-a", updatedAt = Instant.parse("2026-04-30T09:00:00Z"))
        val foreign = chat(userId = "user-b", updatedAt = Instant.parse("2026-04-30T10:00:00Z"))

        repository.create(older)
        repository.create(newer)
        repository.create(foreign)

        assertEquals(newer, repository.get("user-a", newer.id))
        assertNull(repository.get("user-a", foreign.id))
        assertEquals(listOf(newer, older), repository.list("user-a"))
    }

    @Test
    fun `messages append with chat scoped seq and stay isolated by user and chat`() = runTest {
        val repository = MemoryMessageRepository()
        val chatA = UUID.randomUUID()
        val chatB = UUID.randomUUID()

        val first = repository.append(userId = "user-a", chatId = chatA, role = ChatRole.USER, content = "first")
        val second = repository.append(userId = "user-a", chatId = chatA, role = ChatRole.ASSISTANT, content = "second")
        val otherChat = repository.append(userId = "user-a", chatId = chatB, role = ChatRole.USER, content = "third")
        val otherUser = repository.append(userId = "user-b", chatId = chatA, role = ChatRole.USER, content = "fourth")

        assertEquals(1L, first.seq)
        assertEquals(2L, second.seq)
        assertEquals(1L, otherChat.seq)
        assertEquals(1L, otherUser.seq)
        assertEquals(listOf(first, second), repository.list(userId = "user-a", chatId = chatA))
        assertEquals(listOf(otherChat), repository.list(userId = "user-a", chatId = chatB))
        assertEquals(listOf(otherUser), repository.list(userId = "user-b", chatId = chatA))
        assertEquals(second, repository.get(userId = "user-a", chatId = chatA, seq = 2))
    }

    @Test
    fun `agent state is stored separately from messages`() = runTest {
        val messageRepository = MemoryMessageRepository()
        val stateRepository = MemoryAgentStateRepository()
        val chatId = UUID.randomUUID()

        messageRepository.append(userId = "user-a", chatId = chatId, role = ChatRole.USER, content = "visible message")
        val state = agentState(
            userId = "user-a",
            chatId = chatId,
            basedOnMessageSeq = 0,
            history = listOf(
                LLMRequest.Message(
                    role = LLMMessageRole.system,
                    content = "runtime-only state",
                )
            ),
        )
        stateRepository.save(state)

        assertEquals(state, stateRepository.get("user-a", chatId))
        assertEquals(
            listOf("visible message"),
            messageRepository.list("user-a", chatId).map { it.content }
        )
    }

    @Test
    fun `execution choice and event repositories support basic save read list and user isolation`() = runTest {
        val executionRepository = MemoryAgentExecutionRepository()
        val choiceRepository = MemoryChoiceRepository()
        val eventRepository = MemoryAgentEventRepository()
        val chatId = UUID.randomUUID()
        val otherChatId = UUID.randomUUID()

        val execution = execution(userId = "user-a", chatId = chatId)
        val otherExecution = execution(userId = "user-b", chatId = chatId)
        executionRepository.save(execution)
        executionRepository.save(otherExecution)

        val choice = choice(userId = "user-a", chatId = chatId, executionId = execution.id)
        val otherChoice = choice(userId = "user-b", chatId = chatId, executionId = otherExecution.id)
        choiceRepository.save(choice)
        choiceRepository.save(otherChoice)

        val firstEvent = eventRepository.append(
            userId = "user-a",
            chatId = chatId,
            executionId = execution.id,
            type = AgentEventType.EXECUTION_STARTED,
            payload = mapOf("requestId" to "req-1"),
        )
        val secondEvent = eventRepository.append(
            userId = "user-a",
            chatId = chatId,
            executionId = execution.id,
            type = AgentEventType.MESSAGE_COMPLETED,
            payload = mapOf("messageId" to UUID.randomUUID().toString()),
        )
        val foreignEvent = eventRepository.append(
            userId = "user-b",
            chatId = otherChatId,
            executionId = otherExecution.id,
            type = AgentEventType.EXECUTION_STARTED,
            payload = emptyMap(),
        )

        assertEquals(execution, executionRepository.get("user-a", execution.id))
        assertNull(executionRepository.get("user-a", otherExecution.id))
        assertEquals(listOf(execution), executionRepository.listByChat("user-a", chatId))

        assertEquals(choice, choiceRepository.get("user-a", choice.id))
        assertNull(choiceRepository.get("user-a", otherChoice.id))
        assertEquals(listOf(choice), choiceRepository.listByExecution("user-a", chatId, execution.id))

        assertEquals(1L, firstEvent.seq)
        assertEquals(2L, secondEvent.seq)
        assertEquals(1L, foreignEvent.seq)
        assertEquals(firstEvent, eventRepository.get("user-a", firstEvent.id))
        assertEquals(listOf(firstEvent, secondEvent), eventRepository.listByChat("user-a", chatId))
        assertEquals(listOf(secondEvent), eventRepository.listByChat("user-a", chatId, afterSeq = 1L))
        assertEquals(listOf(foreignEvent), eventRepository.listByChat("user-b", otherChatId))
    }

    private fun userSettings(
        userId: String,
        defaultModel: LLMModel = LLMModel.Max,
        contextSize: Int = 16_000,
        temperature: Float = 0.7f,
        locale: Locale = Locale.forLanguageTag("ru-RU"),
        timeZone: ZoneId = ZoneId.of("Europe/Moscow"),
    ): UserSettings =
        UserSettings(
            userId = userId,
            defaultModel = defaultModel,
            contextSize = contextSize,
            temperature = temperature,
            locale = locale,
            timeZone = timeZone,
            systemPrompt = "system-$userId",
            enabledTools = setOf("ListFiles"),
            showToolEvents = true,
            streamingMessages = true,
            toolPermissions = mapOf("ListFiles" to ToolPermission(ToolPermissionMode.ALLOW)),
            mcp = mapOf("repo" to UserMcpServer(enabled = true)),
            createdAt = Instant.parse("2026-04-30T08:00:00Z"),
            updatedAt = Instant.parse("2026-04-30T08:05:00Z"),
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
            createdAt = Instant.parse("2026-04-30T07:00:00Z"),
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
            updatedAt = Instant.parse("2026-04-30T08:15:00Z"),
            rowVersion = 0L,
        )

    private fun execution(
        userId: String,
        chatId: UUID,
    ): AgentExecution =
        AgentExecution(
            id = UUID.randomUUID(),
            userId = userId,
            chatId = chatId,
            userMessageId = UUID.randomUUID(),
            assistantMessageId = null,
            status = AgentExecutionStatus.RUNNING,
            requestId = "req-$userId",
            clientMessageId = "client-$userId",
            model = LLMModel.Max,
            provider = LLMModel.Max.provider,
            startedAt = Instant.parse("2026-04-30T08:20:00Z"),
            finishedAt = null,
            cancelRequested = false,
            errorCode = null,
            errorMessage = null,
            usage = null,
            metadata = mapOf("source" to "test"),
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
            createdAt = Instant.parse("2026-04-30T08:30:00Z"),
            expiresAt = null,
            answeredAt = null,
        )
}
