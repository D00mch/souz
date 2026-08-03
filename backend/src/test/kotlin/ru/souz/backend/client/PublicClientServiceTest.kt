package ru.souz.backend.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.agent.AgentId
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientRequestRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.http.GateControlledChatApi
import ru.souz.backend.http.routeTestContext
import ru.souz.backend.testutil.repository.MemoryAgentExecutionRepository
import ru.souz.backend.testutil.repository.MemoryClientRequestRepository

class PublicClientServiceTest {
    private val json = jacksonObjectMapper()

    @Test
    fun `initial receipt failure rejects without leaving an execution or acknowledgement`() = runBlocking {
        val executionRepository = MemoryAgentExecutionRepository()
        val clientRequestRepository = FailingClientRequestRepository()
        val context = routeTestContext(
            executionRepository = executionRepository,
            clientRequestRepository = clientRequestRepository,
        )
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val frame = json.treeToValue(
            json.readTree(messageFrame(chat.id.toString(), userId, "message-1", null, "Привет", "device-1")),
            MessageSubmitFrame::class.java,
        )

        val handled = context.publicClientService.handleMessage(chat, frame)

        val response = assertIs<RejectedMessageAck>(handled.response)
        assertEquals("message_rejected", response.error.code)
        assertNull(executionRepository.findActive(userId, chat.id))
        val attemptedThreadId = assertNotNull(clientRequestRepository.attemptedExecutionId)
        assertFalse(context.clientThreadRegistry.contains(attemptedThreadId))
    }

    @Test
    fun `startup cancellation after atomic commit fails the queued execution`() = runBlocking {
        val executionRepository = MemoryAgentExecutionRepository()
        val clientRequestRepository = CancellingAfterCommitClientRequestRepository(executionRepository)
        val context = routeTestContext(
            executionRepository = executionRepository,
            clientRequestRepository = clientRequestRepository,
        )
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val frame = json.treeToValue(
            json.readTree(messageFrame(chat.id.toString(), userId, "message-1", null, "Привет", "device-1")),
            MessageSubmitFrame::class.java,
        )

        assertFailsWith<CancellationException> {
            context.publicClientService.handleMessage(chat, frame)
        }
        val receipt = assertNotNull(clientRequestRepository.get(chat.id, "message-1"))
        val threadId = assertNotNull(receipt.threadId)
        val execution = assertNotNull(executionRepository.get(userId, threadId))
        assertEquals(AgentExecutionStatus.FAILED, execution.status)
        assertEquals("Thread startup was interrupted.", execution.errorMessage)
        assertEquals(true, context.clientThreadRegistry.contains(threadId))

        val retry = context.publicClientService.handleMessage(chat, frame)
        val retryAck = assertIs<AcceptedMessageAck>(retry.response)
        assertEquals(true, retryAck.duplicate)
        retry.afterSend()
        withTimeout(200) { context.clientThreadRegistry.awaitAcceptedInputAcks(threadId) }
        assertFalse(context.clientThreadRegistry.contains(threadId))
    }

    @Test
    fun `request serialization does not block unrelated chats`() = runBlocking {
        val executionRepository = MemoryAgentExecutionRepository()
        val blockingRepository = BlockingClientRequestRepository(
            delegate = MemoryClientRequestRepository(executionRepository),
            blockedRequestId = "blocked-message",
        )
        val context = routeTestContext(
            executionRepository = executionRepository,
            clientRequestRepository = blockingRepository,
        )
        val userId = UUID.randomUUID().toString()
        val blockedChat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val otherChat = context.chatService.createClient(userId, "create-2", "backend", null).chat
        val blockedFrame = json.treeToValue(
            json.readTree(messageFrame(blockedChat.id.toString(), userId, "blocked-message", null, "Жди", "device-1")),
            MessageSubmitFrame::class.java,
        )
        val otherThreadId = UUID.randomUUID()
        executionRepository.create(
            AgentExecution(
                id = otherThreadId,
                userId = userId,
                chatId = otherChat.id,
                userMessageId = null,
                assistantMessageId = null,
                status = AgentExecutionStatus.RUNNING,
                requestId = null,
                clientMessageId = null,
                model = null,
                provider = null,
                startedAt = Instant.now(),
                finishedAt = null,
                cancelRequested = false,
                errorCode = null,
                errorMessage = null,
                usage = null,
                metadata = emptyMap(),
            )
        )
        context.clientThreadRegistry.register(
            otherThreadId,
            ClientDevice(userId, "device-2", "tv_box", setOf("speech")),
        )

        val blockedRequest = async { context.publicClientService.handleMessage(blockedChat, blockedFrame) }
        try {
            withTimeout(500) { blockingRepository.awaitBlocked() }
            val handled = withTimeout(500) {
                context.publicClientService.handleCancel(
                    otherChat,
                    ThreadCancelFrame(
                        kind = "thread.cancel",
                        chatId = otherChat.id.toString(),
                        requestId = "cancel-1",
                        threadId = otherThreadId.toString(),
                        reason = "user_requested",
                    ),
                )
            }

            val response = assertIs<ThreadCancelAck>(handled.response)
            assertEquals("accepted", response.status)
            handled.afterSend()
        } finally {
            blockedRequest.cancelAndJoin()
        }
    }

    @Test
    fun `accepted input commit failure propagates without publishing input and clears its acknowledgement`() = runBlocking {
        val api = GateControlledChatApi()
        val context = routeTestContext(
            llmApi = api,
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = false, toolEvents = true),
            agentId = AgentId.SKILLS_GRAPH,
        )
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val firstFrame = json.treeToValue(
            json.readTree(messageFrame(chat.id.toString(), userId, "message-1", null, "Первое сообщение", "device-1")),
            MessageSubmitFrame::class.java,
        )
        val first = context.publicClientService.handleMessage(chat, firstFrame)
        val firstAck = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(first.response)
        val threadId = UUID.fromString(firstAck["thread"]["id"].asText())
        first.afterSend()
        api.awaitStarted("Первое сообщение")

        val failure = assertFailsWith<IllegalStateException> {
            context.clientThreadRegistry.acceptInput(
                threadId = threadId,
                requestId = "message-2",
                device = ClientDevice(userId, "device-2", "tv_box", setOf("speech")),
                input = "Второе сообщение",
                canAccept = { true },
                commit = { error("commit failed") },
            )
        }

        assertEquals("commit failed", failure.message)
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            withTimeout(200) { api.awaitStarted("Второе сообщение") }
        }
        withTimeout(200) {
            context.clientThreadRegistry.awaitAcceptedInputAcks(threadId)
        }
        api.release()
        withTimeout(2_000) {
            while (context.eventRepository.listByChat(userId, chat.id).none {
                    it.type == AgentEventType.THREAD_COMPLETED
                }) {
                delay(10)
            }
        }
    }

    @Test
    fun `accepted cancellation receipt failure clears its acknowledgement`() = runBlocking {
        val api = GateControlledChatApi()
        val context = routeTestContext(
            llmApi = api,
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = false, toolEvents = true),
            agentId = AgentId.SKILLS_GRAPH,
        )
        val userId = UUID.randomUUID().toString()
        val chat = context.chatService.createClient(userId, "create-1", "backend", null).chat
        val firstFrame = json.treeToValue(
            json.readTree(messageFrame(chat.id.toString(), userId, "message-1", null, "Отмени меня", "device-1")),
            MessageSubmitFrame::class.java,
        )
        val first = context.publicClientService.handleMessage(chat, firstFrame)
        val firstAck = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(first.response)
        val threadId = UUID.fromString(firstAck["thread"]["id"].asText())
        first.afterSend()
        api.awaitStarted("Отмени меня")

        val failingService = PublicClientService(
            chatRepository = context.chatRepository,
            executionRepository = context.executionRepository,
            clientInputRepository = context.clientInputRepository,
            clientRequestRepository = FailingClientRequestRepository(),
            toolCallRepository = context.toolCallRepository,
            executionService = context.executionService,
            registry = context.clientThreadRegistry,
        )
        val failure = assertFailsWith<IllegalStateException> {
            failingService.handleCancel(
                chat,
                ThreadCancelFrame(
                    kind = "thread.cancel",
                    chatId = chat.id.toString(),
                    requestId = "cancel-1",
                    threadId = threadId.toString(),
                    reason = "user_requested",
                ),
            )
        }

        assertEquals("receipt failed", failure.message)
        withTimeout(200) {
            context.clientThreadRegistry.awaitAcceptedInputAcks(threadId)
        }
        api.release()
    }

    private fun messageFrame(
        chatId: String,
        userId: String,
        requestId: String,
        threadId: String?,
        text: String,
        deviceId: String,
    ): String {
        val thread = threadId?.let { ",\"threadId\":\"$it\"" }.orEmpty()
        return """{"kind":"message.submit","chatId":"$chatId","requestId":"$requestId"$thread,"payload":{"device":{"userId":"$userId","deviceId":"$deviceId","deviceType":"tv_box","capabilities":["speech","screen","device_tools"]},"content":{"type":"text","source":"voice","text":"$text"},"meta":{"locale":"ru-RU","timeZone":"Europe/Moscow"}}}"""
    }
}

private class FailingClientRequestRepository : ClientRequestRepository {
    var attemptedExecutionId: UUID? = null
        private set

    override suspend fun create(request: ClientRequest): ClientRequest = error("receipt failed")

    override suspend fun createWithExecution(
        execution: AgentExecution,
        request: ClientRequest,
    ): AgentExecution {
        attemptedExecutionId = execution.id
        error("receipt failed")
    }

    override suspend fun get(chatId: UUID, requestId: String): ClientRequest? = null
}

private class CancellingAfterCommitClientRequestRepository(
    executionRepository: MemoryAgentExecutionRepository,
) : ClientRequestRepository {
    private val delegate = MemoryClientRequestRepository(executionRepository)

    override suspend fun create(request: ClientRequest): ClientRequest = delegate.create(request)

    override suspend fun createWithExecution(
        execution: AgentExecution,
        request: ClientRequest,
    ): AgentExecution {
        delegate.createWithExecution(execution, request)
        throw CancellationException("cancelled after commit")
    }

    override suspend fun get(chatId: UUID, requestId: String): ClientRequest? = delegate.get(chatId, requestId)
}

private class BlockingClientRequestRepository(
    private val delegate: ClientRequestRepository,
    private val blockedRequestId: String,
) : ClientRequestRepository by delegate {
    private val blocked = CompletableDeferred<Unit>()

    override suspend fun get(chatId: UUID, requestId: String): ClientRequest? {
        if (requestId != blockedRequestId) return delegate.get(chatId, requestId)
        blocked.complete(Unit)
        awaitCancellation()
    }

    suspend fun awaitBlocked() = blocked.await()
}
