package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory

internal const val USER_ASK_SKILL = "user.ask"
internal const val DEVICE_MEDIA_OPEN_SKILL = "device.media.open"
internal val CLIENT_SKILL_NAMES = setOf(USER_ASK_SKILL, DEVICE_MEDIA_OPEN_SKILL)

internal class BackendClientToolCatalog(
    registry: ClientThreadRuntimeRegistry,
    toolCallRepository: ToolCallRepository,
    eventService: AgentEventService,
) : AgentToolCatalog {
    private val ask = ClientToolSetup(
        name = USER_ASK_SKILL,
        description = "Ask the user a short clarification question and wait for their answer.",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf("question" to LLMRequest.Property("string", "Question shown to the user.")),
            required = listOf("question"),
        ),
        timeout = Duration.ofMinutes(5),
        registry = registry,
        toolCallRepository = toolCallRepository,
        eventService = eventService,
    )
    private val mediaOpen = ClientToolSetup(
        name = DEVICE_MEDIA_OPEN_SKILL,
        description = "Open media on the user's latest device and wait for the device result.",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "query" to LLMRequest.Property("string", "Media title or search query."),
                "genre" to LLMRequest.Property("string", "Optional media genre."),
            ),
            required = listOf("query"),
        ),
        timeout = Duration.ofMinutes(1),
        registry = registry,
        toolCallRepository = toolCallRepository,
        eventService = eventService,
    )

    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = mapOf(
        ToolCategory.CHAT to mapOf(ask.fn.name to ask),
        ToolCategory.APPLICATIONS to mapOf(mediaOpen.fn.name to mediaOpen),
    )
}

private class ClientToolSetup(
    name: String,
    description: String,
    parameters: LLMRequest.Parameters,
    private val timeout: Duration,
    private val registry: ClientThreadRuntimeRegistry,
    private val toolCallRepository: ToolCallRepository,
    private val eventService: AgentEventService,
) : LLMToolSetup {
    override val fn = LLMRequest.Function(name = name, description = description, parameters = parameters)

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        errorMessage(functionCall.name, "client_context_missing", "Client tool context is unavailable.")

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val threadId = meta.requestId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: return errorMessage(functionCall.name, "client_context_missing", "Thread ID is unavailable.")
        val chatId = meta.conversationId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: return errorMessage(functionCall.name, "client_context_missing", "Chat ID is unavailable.")
        val device = registry.latestDevice(threadId)
            ?: return errorMessage(functionCall.name, "client_context_missing", "Client device is unavailable.")
        val toolCallId = UUID.randomUUID().toString()
        val pending = PendingClientTool(toolCallId)
        if (!registry.beginTool(threadId, pending)) {
            return errorMessage(functionCall.name, "client_tool_busy", "Another client tool call is already pending.")
        }
        val startedAt = Instant.now()
        val deadlineAt = startedAt.plus(timeout)
        val arguments = restJsonMapper.valueToTree<JsonNode>(functionCall.arguments)
        val context = ToolCallContext(meta.userId, chatId.toString(), threadId.toString(), toolCallId)
        try {
            toolCallRepository.startClientCall(
                context = context,
                name = fn.name,
                deviceId = device.deviceId,
                argumentsJson = restJsonMapper.writeValueAsString(arguments),
                deadlineAt = deadlineAt,
                startedAt = startedAt,
            )
            registry.awaitAcceptedInputAcks(threadId)
            eventService.appendDurable(
                userId = meta.userId,
                chatId = chatId,
                executionId = threadId,
                type = AgentEventType.TOOL_CALL_STARTED,
                payload = PublicToolCallStartedPayload(
                    toolCallId = toolCallId,
                    name = fn.name,
                    deviceId = device.deviceId,
                    arguments = arguments,
                    deadlineAt = deadlineAt.toString(),
                ),
            )
            val outcome = withTimeoutOrNull(timeout.toMillis()) { pending.result.await() }
                ?: timeOut(context, threadId, toolCallId, pending)
            return LLMRequest.Message(
                role = LLMMessageRole.function,
                content = when (outcome.status) {
                    "succeeded" -> restJsonMapper.writeValueAsString(outcome.result)
                    else -> restJsonMapper.writeValueAsString(
                        mapOf(
                            "status" to outcome.status,
                            "error" to (outcome.error ?: ClientError("client_tool_failed", "Client tool failed.")),
                        )
                    )
                },
                name = functionCall.name,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return errorMessage(functionCall.name, "client_tool_failed", error.message ?: "Client tool failed.")
        } finally {
            registry.clearTool(threadId, toolCallId)
        }
    }

    private suspend fun timeOut(
        context: ToolCallContext,
        threadId: UUID,
        toolCallId: String,
        pending: PendingClientTool,
    ): ClientToolOutcome {
        val error = ClientError("client_tool_timed_out", "Client tool result deadline expired.")
        val errorNode = restJsonMapper.valueToTree<JsonNode>(error)
        val payloadHash = PublicPayloadHash.ofValue(mapOf("status" to "timed_out", "error" to error))
        val completed = toolCallRepository.completeClientCall(
            context = context,
            status = ToolCallStatus.TIMED_OUT,
            resultJson = null,
            errorJson = restJsonMapper.writeValueAsString(errorNode),
            payloadHash = payloadHash,
        )
        if (completed == null) return pending.result.await()
        val outcome = ClientToolOutcome("timed_out", null, error)
        registry.finishTool(threadId, toolCallId, outcome)
        return outcome
    }

    private fun errorMessage(functionName: String, code: String, message: String): LLMRequest.Message =
        LLMRequest.Message(
            role = LLMMessageRole.function,
            content = restJsonMapper.writeValueAsString(mapOf("error" to ClientError(code, message))),
            name = functionName,
        )
}
