package ru.souz.backend.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.souz.agent.AgentId
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientRequestRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.http.GateControlledChatApi
import ru.souz.backend.http.routeTestContext

class PublicClientServiceTest {
    private val json = jacksonObjectMapper()

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
    override suspend fun create(request: ClientRequest): ClientRequest = error("receipt failed")

    override suspend fun get(chatId: UUID, requestId: String): ClientRequest? = null
}
