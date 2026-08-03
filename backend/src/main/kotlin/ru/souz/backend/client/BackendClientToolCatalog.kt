package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.agent.skills.bundle.SkillBundleParser
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

internal const val CLIENT_WEBSOCKET_SKILL = "client.websocket"
internal val CLIENT_SKILL_NAMES = setOf(CLIENT_WEBSOCKET_SKILL)

internal class BackendClientToolCatalog(
    registry: ClientThreadRuntimeRegistry,
    toolCallRepository: ToolCallRepository,
    eventService: AgentEventService,
    now: () -> Instant = Instant::now,
) : AgentToolCatalog {
    private val clientWebSocket = ClientWebSocketSkill(
        registry = registry,
        toolCallRepository = toolCallRepository,
        eventService = eventService,
        now = now,
    )

    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = mapOf(
        ToolCategory.CHAT to mapOf(clientWebSocket.fn.name to clientWebSocket),
    )
}

private class ClientWebSocketSkill(
    private val registry: ClientThreadRuntimeRegistry,
    private val toolCallRepository: ToolCallRepository,
    private val eventService: AgentEventService,
    private val now: () -> Instant,
) : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = CLIENT_WEBSOCKET_SKILL,
        description = CLIENT_WEBSOCKET_SKILL_DESCRIPTION,
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "name" to LLMRequest.Property("string", "Operation name sent to the client."),
                "arguments" to LLMRequest.Property("object", "Operation-specific JSON payload."),
                "timeoutSeconds" to LLMRequest.Property(
                    "number",
                    "Optional result deadline from 1 to $MAX_TIMEOUT_SECONDS seconds.",
                ),
            ),
            required = listOf("name", "arguments"),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        errorMessage(functionCall.name, "client_context_missing", "Client tool context is unavailable.")

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val operationName = (functionCall.arguments["name"] as? String)?.trim().orEmpty()
        if (operationName.isEmpty()) {
            return errorMessage(functionCall.name, "invalid_client_message", "Client operation name is required.")
        }
        val arguments = restJsonMapper.valueToTree<JsonNode>(functionCall.arguments["arguments"])
        if (!arguments.isObject) {
            return errorMessage(functionCall.name, "invalid_client_message", "Client arguments must be an object.")
        }
        val timeoutSeconds = (functionCall.arguments["timeoutSeconds"] as? Number)
            ?.toLong()
            ?.coerceIn(1, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS
        val threadId = meta.requestId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: return errorMessage(functionCall.name, "client_context_missing", "Thread ID is unavailable.")
        val chatId = meta.conversationId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: return errorMessage(functionCall.name, "client_context_missing", "Chat ID is unavailable.")
        val toolCallId = UUID.randomUUID().toString()
        val pending = PendingClientTool(toolCallId)
        val device = when (val beginTool = registry.beginTool(threadId, pending)) {
            BeginClientToolResult.Missing ->
                return errorMessage(functionCall.name, "client_context_missing", "Client device is unavailable.")
            BeginClientToolResult.Busy ->
                return errorMessage(functionCall.name, "client_tool_busy", "Another client tool call is already pending.")
            is BeginClientToolResult.Started -> beginTool.device
        }
        val startedAt = now()
        val deadlineAt = startedAt.plusSeconds(timeoutSeconds)
        val context = ToolCallContext(meta.userId, chatId.toString(), threadId.toString(), toolCallId)
        var clientCallStarted = false
        try {
            toolCallRepository.startClientCall(
                context = context,
                name = operationName,
                deviceId = device.deviceId,
                argumentsJson = restJsonMapper.writeValueAsString(arguments),
                deadlineAt = deadlineAt,
                startedAt = startedAt,
            )
            clientCallStarted = true
            registry.awaitAcceptedInputAcks(threadId)
            eventService.appendDurable(
                userId = meta.userId,
                chatId = chatId,
                executionId = threadId,
                type = AgentEventType.TOOL_CALL_STARTED,
                payload = PublicToolCallStartedPayload(
                    toolCallId = toolCallId,
                    name = operationName,
                    deviceId = device.deviceId,
                    arguments = arguments,
                    deadlineAt = deadlineAt.toString(),
                ),
            )
            val outcome = awaitResultUntilDeadline(context, threadId, toolCallId, pending, deadlineAt)
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
            withContext(NonCancellable) {
                cancel(context)
            }
            throw cancelled
        } catch (error: Exception) {
            if (clientCallStarted) {
                withContext(NonCancellable) {
                    failStartedCall(context, error)
                }
            }
            return errorMessage(functionCall.name, "client_tool_failed", error.message ?: "Client tool failed.")
        } finally {
            registry.clearTool(threadId, toolCallId)
        }
    }

    private suspend fun awaitResultUntilDeadline(
        context: ToolCallContext,
        threadId: UUID,
        toolCallId: String,
        pending: PendingClientTool,
        deadlineAt: Instant,
    ): ClientToolOutcome {
        val remainingMillis = Duration.between(now(), deadlineAt).toMillis()
        val completed = if (remainingMillis > 0) {
            withTimeoutOrNull(remainingMillis) { pending.result.await() }
        } else {
            null
        }
        return completed ?: timeOut(context, threadId, toolCallId, pending)
    }

    private suspend fun failStartedCall(context: ToolCallContext, cause: Exception) {
        val error = ClientError("client_tool_failed", cause.message ?: "Client tool failed.")
        toolCallRepository.completeClientCall(
            context = context,
            status = ToolCallStatus.FAILED,
            resultJson = null,
            errorJson = restJsonMapper.writeValueAsString(error),
            payloadHash = PublicPayloadHash.ofValue(mapOf("status" to "failed", "error" to error)),
        )
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
            receivedAt = now(),
        )
        if (completed == null) return pending.result.await()
        val outcome = ClientToolOutcome("timed_out", null, error)
        registry.finishTool(threadId, toolCallId, outcome)
        return outcome
    }

    private suspend fun cancel(context: ToolCallContext) {
        val error = ClientError("client_tool_cancelled", "Client tool call was cancelled with its thread.")
        toolCallRepository.completeClientCall(
            context = context,
            status = ToolCallStatus.CANCELLED,
            resultJson = null,
            errorJson = restJsonMapper.writeValueAsString(error),
            payloadHash = PublicPayloadHash.ofValue(mapOf("status" to "cancelled", "error" to error)),
        )
    }

    private fun errorMessage(functionName: String, code: String, message: String): LLMRequest.Message =
        LLMRequest.Message(
            role = LLMMessageRole.function,
            content = restJsonMapper.writeValueAsString(mapOf("error" to ClientError(code, message))),
            name = functionName,
        )

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 300L
        const val MAX_TIMEOUT_SECONDS = 300L
    }
}

private val CLIENT_WEBSOCKET_SKILL_DESCRIPTION: String by lazy {
    val markdown = requireNotNull(
        BackendClientToolCatalog::class.java.getResource("/skills/client-websocket/SKILL.md")
    ) { "Missing client WebSocket Skill resource." }.readText()
    SkillBundleParser.parse(markdown).let { parsed ->
        "${parsed.manifest.description}\n\n${parsed.body}"
    }
}
