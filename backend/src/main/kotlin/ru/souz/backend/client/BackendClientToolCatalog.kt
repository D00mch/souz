package ru.souz.backend.client

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.toolcall.model.ToolCall
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

internal class BackendClientToolCatalogFactory(
    private val registry: ClientThreadRuntimeRegistry,
    private val toolCallRepository: ToolCallRepository,
    private val eventService: AgentEventService,
    private val now: () -> Instant = Instant::now,
    private val userAskTimeout: Duration = Duration.ofMinutes(5),
    private val deviceMediaOpenTimeout: Duration = Duration.ofMinutes(1),
) {
    init {
        require(!userAskTimeout.isZero && !userAskTimeout.isNegative) {
            "userAskTimeout must be positive."
        }
        require(!deviceMediaOpenTimeout.isZero && !deviceMediaOpenTimeout.isNegative) {
            "deviceMediaOpenTimeout must be positive."
        }
    }

    fun create(): AgentToolCatalog =
        BackendClientToolCatalog(
            tools = clientToolDefinitions(
                userAskTimeout = userAskTimeout,
                deviceMediaOpenTimeout = deviceMediaOpenTimeout,
            ),
            registry = registry,
            toolCallRepository = toolCallRepository,
            eventService = eventService,
            now = now,
        )
}

private class BackendClientToolCatalog(
    tools: List<ClientToolDefinition>,
    registry: ClientThreadRuntimeRegistry,
    toolCallRepository: ToolCallRepository,
    eventService: AgentEventService,
    now: () -> Instant,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = tools
        .groupBy { it.category }
        .mapValues { (_, categoryTools) ->
            categoryTools.associate { tool ->
                tool.name to ClientWebSocketTool(tool, registry, toolCallRepository, eventService, now)
            }
        }
}

private class ClientWebSocketTool(
    private val tool: ClientToolDefinition,
    private val registry: ClientThreadRuntimeRegistry,
    private val toolCallRepository: ToolCallRepository,
    private val eventService: AgentEventService,
    private val now: () -> Instant,
) : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = tool.name,
        description = tool.description,
        parameters = tool.parameters,
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        errorMessage(functionCall.name, "client_context_missing", "Client tool context is unavailable.")

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        invalidStringArgument(functionCall.arguments)?.let { argumentName ->
            return errorMessage(
                functionName = functionCall.name,
                code = "invalid_arguments",
                message = "Argument '$argumentName' must be a string.",
            )
        }
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
        val deadlineAt = startedAt.plus(tool.timeout)
        val arguments = restJsonMapper.valueToTree<JsonNode>(functionCall.arguments)
        val context = ToolCallContext(meta.userId, chatId.toString(), threadId.toString(), toolCallId)
        var clientCallStarted = false
        try {
            toolCallRepository.startClientCall(
                context = context,
                name = fn.name,
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
                    name = fn.name,
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

    private fun invalidStringArgument(arguments: Map<String, Any>): String? =
        tool.parameters.properties.entries.firstOrNull { (argumentName, property) ->
            property.type == "string" && when {
                argumentName in tool.parameters.required -> arguments[argumentName] !is String
                argumentName in arguments -> arguments[argumentName] !is String
                else -> false
            }
        }?.key

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
        if (completed == null) {
            val storedOutcome = toolCallRepository.get(context)
                ?.takeIf { it.target == "client" && it.status != ToolCallStatus.RUNNING }
                ?.toClientToolOutcome()
            if (storedOutcome != null) {
                registry.finishTool(threadId, toolCallId, storedOutcome)
                return storedOutcome
            }
            val outcome = ClientToolOutcome("timed_out", null, error)
            registry.finishTool(threadId, toolCallId, outcome)
            return outcome
        }
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

    private fun ToolCall.toClientToolOutcome(): ClientToolOutcome =
        ClientToolOutcome(
            status = status.value,
            result = resultJson?.let { restJsonMapper.readTree(it) },
            error = errorJson?.let { stored ->
                runCatching { restJsonMapper.readValue(stored, ClientError::class.java) }
                    .getOrElse { ClientError("client_tool_failed", "Client tool failed.") }
            },
        )
}

private data class ClientToolDefinition(
    val name: String,
    val category: ToolCategory,
    val timeout: Duration,
    val description: String,
    val parameters: LLMRequest.Parameters,
)

private fun clientToolDefinitions(
    userAskTimeout: Duration,
    deviceMediaOpenTimeout: Duration,
): List<ClientToolDefinition> = listOf(
    ClientToolDefinition(
        name = "user.ask",
        category = ToolCategory.CHAT,
        timeout = userAskTimeout,
        description = buildString {
            append("Ask the user a concise clarification question over the active public Souz WebSocket ")
            append("and wait for their answer. Use the returned answer; do not invent the user's response.")
        },
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "question" to LLMRequest.Property("string", "Question shown to the user."),
            ),
            required = listOf("question"),
        ),
    ),
    ClientToolDefinition(
        name = "device.media.open",
        category = ToolCategory.APPLICATIONS,
        timeout = deviceMediaOpenTimeout,
        description = buildString {
            append("Open media on the user's active client device over the public Souz WebSocket and wait for the ")
            append("device result. Claim success only when the result reports that the media was opened.")
        },
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "query" to LLMRequest.Property("string", "Media title or search query."),
                "genre" to LLMRequest.Property("string", "Optional media genre."),
            ),
            required = listOf("query"),
        ),
    ),
)
