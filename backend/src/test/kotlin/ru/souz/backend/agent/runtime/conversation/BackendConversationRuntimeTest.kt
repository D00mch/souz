package ru.souz.backend.agent.runtime.conversation

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import ru.souz.agent.ActiveRunMailbox
import ru.souz.agent.ActiveRunInput
import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.AgentExecutor
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationSettingsProvider
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.chat.model.CLIENT_HISTORY_MESSAGE_METADATA_KEY
import ru.souz.backend.chat.model.CROSS_CHANNEL_MESSAGE_METADATA_KEY
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientRequestResult
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.llm.BackendExecutionLlmChatApi
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest

class BackendConversationRuntimeTest {
    @Test
    fun `execute startup paginates history only through its durable input barrier`() = runTest {
        val fixture = Fixture(
            messages = (1L..1_001L).map { seq -> historyMessage(seq, roleFor(seq), "history-$seq") } +
                crossChannelMessage(1_003L, "too-late"),
        )

        val loaded = loadInitialConversationMessages(
            messageRepository = fixture.repository,
            key = fixture.key,
            basedOnMessageSeq = 0L,
            inputMessageSeq = 1_002L,
        )

        assertEquals(1_002L, loaded.observedThroughSeq)
        assertEquals(1_001, loaded.messages.size)
        assertEquals("history-1", loaded.messages.first().content)
        assertEquals("history-1001", loaded.messages.last().content)
        assertEquals(3, fixture.repository.listCalls)
    }

    @Test
    fun `option startup stops before client history without skipping its cursor gap`() = runTest {
        val fixture = Fixture(
            messages = listOf(
                crossChannelMessage(1L, "visible cross-channel"),
                ordinaryMessage(2L, ChatRole.ASSISTANT, "already in session"),
                historyMessage(3L, ChatRole.USER, "pending history"),
                crossChannelMessage(4L, "blocked behind history"),
            ),
        )

        val loaded = loadInitialConversationMessages(
            messageRepository = fixture.repository,
            key = fixture.key,
            basedOnMessageSeq = 0L,
            inputMessageSeq = null,
        )

        assertEquals(2L, loaded.observedThroughSeq)
        assertEquals(
            listOf(LLMRequest.Message(LLMMessageRole.assistant, "visible cross-channel")),
            loaded.messages,
        )
    }

    @Test
    fun `active execute publishes durable history roles and advances one shared cursor`() = runTest {
        val fixture = Fixture()
        val trigger = ordinaryMessage(14L, ChatRole.USER, "execute")
        val accepted = ClientRequestResult.Accepted(
            request = mockk<ClientRequest>(),
            execution = mockk<AgentExecution>(),
            message = trigger,
            messageDelta = listOf(
                historyMessage(11L, ChatRole.USER, "user history"),
                ordinaryMessage(12L, ChatRole.ASSISTANT, "already represented"),
                crossChannelMessage(13L, "cross-channel"),
                trigger,
            ),
        )
        var commitAfterSeq = -1L
        val runtime = fixture.runtime(initialObservedSeq = 10L)
        var result: ClientRequestResult? = null
        var nextCommitAfterSeq = -1L
        val duplicate = mockk<ClientRequestResult.Duplicate>()

        runtime.execute(
            request = turnRequest(),
            persistSession = false,
            onRuntimeReady = {
                result = runtime.commitActiveRunInput { afterSeq ->
                    commitAfterSeq = afterSeq
                    accepted
                }
                runtime.commitActiveRunInput { afterSeq ->
                    nextCommitAfterSeq = afterSeq
                    duplicate
                }
            },
        )

        assertSame(accepted, result)
        assertEquals(10L, commitAfterSeq)
        assertEquals("execute", fixture.publishedInputs.single().input)
        assertEquals(
            listOf(
                LLMRequest.Message(LLMMessageRole.user, "user history"),
                LLMRequest.Message(LLMMessageRole.assistant, "cross-channel"),
            ),
            fixture.publishedInputs.single().history,
        )
        assertEquals(14L, nextCommitAfterSeq)
    }

    @Test
    fun `fixed boundary loader pages passive history without duplicating execute catch-up`() = runTest {
        val fixture = Fixture(
            messages = (11L..1_011L).map { seq -> historyMessage(seq, roleFor(seq), "history-$seq") },
        )
        val runtime = fixture.runtime(initialObservedSeq = 10L)

        assertTrue(runtime.notifyHistoryPending(1_011L))
        val nextTrigger = ordinaryMessage(1_012L, ChatRole.USER, "next execute")
        var commitAfterSeq = -1L
        var loaded = emptyList<LLMRequest.Message>()
        var emptyReload = listOf(LLMRequest.Message(LLMMessageRole.user, "not empty"))

        runtime.execute(
            request = turnRequest(),
            persistSession = false,
            onRuntimeReady = {
                loaded = fixture.loadHistoryAtBoundary()
                runtime.commitActiveRunInput { afterSeq ->
                    commitAfterSeq = afterSeq
                    ClientRequestResult.Accepted(
                        request = mockk<ClientRequest>(),
                        execution = mockk<AgentExecution>(),
                        message = nextTrigger,
                        messageDelta = listOf(nextTrigger),
                    )
                }
                emptyReload = fixture.loadHistoryAtBoundary()
            },
        )

        assertEquals(1_001, loaded.size)
        assertEquals(1_011L, commitAfterSeq)
        assertEquals(emptyList(), fixture.publishedInputs.single().history)
        assertEquals("next execute", fixture.publishedInputs.single().input)
        assertEquals(emptyList(), emptyReload)
        assertEquals(3, fixture.repository.listCalls)
        assertFalse(runtime.notifyHistoryPending(1_013L))
    }

    private class Fixture(messages: List<ChatMessage> = emptyList()) {
        val key = AgentConversationKey(userId = "user-1", conversationId = TEST_CHAT_ID.toString())
        val repository = InMemoryMessageRepository(messages)
        val executor = mockk<AgentExecutor>()
        val publishedInputs = mutableListOf<ActiveRunInput>()
        private val mailbox = mockk<ActiveRunMailbox>()
        private val contextFactory = mockk<AgentContextFactory>()
        private val settingsProvider = mockk<BackendConversationSettingsProvider>()
        private var historyLoader: (suspend () -> List<LLMRequest.Message>)? = null

        init {
            every { settingsProvider.gigaModel } returns LLMModel.OpenAIGpt5Mini
            every { settingsProvider.temperature } returns 0f
            every { contextFactory.create(any(), any(), any(), any(), any(), any()) } returns baseContext()
            coEvery { mailbox.submit(any()) } coAnswers {
                firstArg<suspend () -> ActiveRunInput?>().invoke()
                    ?.also(publishedInputs::add) != null
            }
            coEvery {
                executor.execute(any(), any(), any(), any(), any(), any())
            } coAnswers {
                historyLoader = arg(4)
                arg<suspend (ActiveRunMailbox) -> Unit>(5).invoke(mailbox)
                AgentExecutionResult(
                    output = "done",
                    context = baseContext().copy(input = "done"),
                )
            }
        }

        suspend fun loadHistoryAtBoundary(): List<LLMRequest.Message> =
            checkNotNull(historyLoader).invoke()

        fun runtime(initialObservedSeq: Long): BackendConversationRuntime = BackendConversationRuntime(
            key = key,
            sessionRepository = mockk<AgentSessionRepository>(relaxed = true),
            settingsProvider = settingsProvider,
            contextFactory = contextFactory,
            executor = executor,
            executionApi = mockk<BackendExecutionLlmChatApi>(relaxed = true),
            persistedSession = null,
            messageRepository = repository,
            initialMessages = emptyList(),
            initialObservedMessageSeq = initialObservedSeq,
        )

        private fun baseContext(): AgentContext<String> = AgentContext(
            input = "",
            settings = AgentSettings(
                model = LLMModel.OpenAIGpt5Mini.alias,
                temperature = 0f,
                toolsByCategory = emptyMap(),
            ),
            history = emptyList(),
            activeTools = emptyList(),
            systemPrompt = "system",
        )
    }

    private class InMemoryMessageRepository(
        private val messages: List<ChatMessage>,
    ) : MessageRepository {
        var listCalls: Int = 0

        override suspend fun append(
            userId: String,
            chatId: UUID,
            role: ChatRole,
            content: String,
            metadata: Map<String, String>,
            id: UUID,
            createdAt: Instant,
        ): ChatMessage = error("Not used")

        override suspend fun get(userId: String, chatId: UUID, seq: Long): ChatMessage? =
            messages.singleOrNull { it.userId == userId && it.chatId == chatId && it.seq == seq }

        override suspend fun getById(userId: String, chatId: UUID, messageId: UUID): ChatMessage? =
            messages.singleOrNull { it.userId == userId && it.chatId == chatId && it.id == messageId }

        override suspend fun latest(userId: String, chatId: UUID): ChatMessage? =
            messages.filter { it.userId == userId && it.chatId == chatId }.maxByOrNull(ChatMessage::seq)

        override suspend fun updateContent(
            userId: String,
            chatId: UUID,
            messageId: UUID,
            content: String,
        ): ChatMessage? = error("Not used")

        override suspend fun list(
            userId: String,
            chatId: UUID,
            afterSeq: Long?,
            beforeSeq: Long?,
            limit: Int,
        ): List<ChatMessage> {
            listCalls += 1
            return messages.asSequence()
                .filter { it.userId == userId && it.chatId == chatId }
                .filter { afterSeq == null || it.seq > afterSeq }
                .filter { beforeSeq == null || it.seq < beforeSeq }
                .sortedBy(ChatMessage::seq)
                .take(limit)
                .toList()
        }
    }

    companion object {
        private val TEST_CHAT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        private fun historyMessage(seq: Long, role: ChatRole, content: String): ChatMessage =
            message(seq, role, content, mapOf(CLIENT_HISTORY_MESSAGE_METADATA_KEY to "true"))

        private fun crossChannelMessage(seq: Long, content: String): ChatMessage =
            message(seq, ChatRole.ASSISTANT, content, mapOf(CROSS_CHANNEL_MESSAGE_METADATA_KEY to "true"))

        private fun ordinaryMessage(seq: Long, role: ChatRole, content: String): ChatMessage =
            message(seq, role, content, emptyMap())

        private fun message(
            seq: Long,
            role: ChatRole,
            content: String,
            metadata: Map<String, String>,
        ) = ChatMessage(
            id = UUID.randomUUID(),
            userId = "user-1",
            chatId = TEST_CHAT_ID,
            seq = seq,
            role = role,
            content = content,
            metadata = metadata,
            createdAt = Instant.EPOCH.plusSeconds(seq),
        )

        private fun roleFor(seq: Long): ChatRole =
            if (seq % 2L == 0L) ChatRole.ASSISTANT else ChatRole.USER

        private fun turnRequest() = BackendConversationTurnRequest(
            prompt = "initial execute",
            model = LLMModel.OpenAIGpt5Mini,
            contextSize = 8_192,
            locale = "en",
            timeZone = "UTC",
            executionId = UUID.randomUUID().toString(),
        )
    }
}
