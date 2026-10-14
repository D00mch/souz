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
import ru.souz.backend.channels.ChannelDeliveryService
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.backend.metrics.metricOutcome
import ru.souz.backend.toolcall.model.ToolCallStatus
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.immutableToolCatalogFromLists
import ru.souz.tool.ToolCategory

internal class BackendClientSkills(
    private val registry: ClientThreadRuntimeRegistry,
    private val toolCallRepository: ToolCallRepository,
    private val eventService: AgentEventService,
    private val channelDeliveryService: ChannelDeliveryService,
    private val now: () -> Instant = Instant::now,
    private val metrics: BackendMetrics? = null,
) {
    fun catalog(definitions: List<ClientToolDefinition>): AgentToolCatalog = immutableToolCatalogFromLists(
        definitions.groupBy { if (it.name == "web.search") ToolCategory.WEB_SEARCH else ToolCategory.APPLICATIONS }
            .mapValues { (category, tools) ->
                tools.map { definition ->
                    ClientWebSocketSkill(definition, category, registry, toolCallRepository, eventService, channelDeliveryService, now, metrics)
                }
            },
    )
}

private class ClientWebSocketSkill(
    definition: ClientToolDefinition,
    category: ToolCategory,
    private val registry: ClientThreadRuntimeRegistry,
    private val toolCallRepository: ToolCallRepository,
    private val eventService: AgentEventService,
    private val channelDeliveryService: ChannelDeliveryService,
    private val now: () -> Instant,
    private val metrics: BackendMetrics?,
) : LLMToolSetup {
    private val timeout = Duration.ofMinutes(1)
    private val metricCategory = category.name.lowercase()

    override val fn = LLMRequest.Function(
        name = definition.name,
        description = definition.description,
        parameters = definition.parameters,
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        outcomeMessage(functionCall.name, failed("client_context_missing", "Client tool context is unavailable."))

    // Count cancellation synchronously before propagating it to the caller.
    @Suppress("SuspendFunSwallowedCancellation")
    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val started = System.nanoTime()
        var outcome = "error"
        metrics?.toolStarted()
        try {
            val received = invokeClient(functionCall, meta)
            val result = if (received.status == "succeeded" && received.result?.isObject != true) {
                failed("invalid_tool_result", "Successful client tool results must be JSON objects.")
            } else received
            outcome = when (result.status) {
                "succeeded" -> "success"
                "timed_out" -> "timeout"
                "cancelled" -> "cancelled"
                else -> "error"
            }
            return outcomeMessage(functionCall.name, result)
        } catch (failure: Throwable) {
            outcome = failure.metricOutcome()
            throw failure
        } finally {
            metrics?.toolFinished()
            metrics?.toolOutcome(metricCategory, outcome, System.nanoTime() - started)
        }
    }

    private suspend fun invokeClient(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): ClientToolOutcome {
        val arguments = restJsonMapper.valueToTree<JsonNode>(functionCall.arguments - "channelId")
        clientArgumentsError(fn.parameters, arguments)?.let { return failed("mcp_invalid_arguments", it) }
        if ("channelId" in functionCall.arguments) return invokeOnChannel(functionCall, meta)
        val threadId = meta.requestId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: return failed("client_context_missing", "Thread ID is unavailable.")
        val chatId = meta.conversationId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
            ?: return failed("client_context_missing", "Chat ID is unavailable.")
        val toolCallId = UUID.randomUUID().toString()
        val pending = PendingClientTool(toolCallId)
        val device = when (val beginTool = registry.beginTool(threadId, pending)) {
            BeginClientToolResult.Missing ->
                return failed("client_context_missing", "Client device is unavailable. Use ListActiveChannels and pass channelId to reach another device.")
            BeginClientToolResult.Busy ->
                return failed("client_tool_busy", "Another client tool call is already pending.")
            is BeginClientToolResult.Started -> beginTool.device
        }
        val startedAt = now()
        val deadlineAt = startedAt.plus(timeout)
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
            return metrics?.executions?.beginClientWait(threadId).use {
                awaitResultUntilDeadline(context, threadId, toolCallId, pending, deadlineAt)
            }
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
            return failed("client_tool_failed", error.message ?: "Client tool failed.")
        } finally {
            withContext(NonCancellable) { registry.clearTool(threadId, toolCallId) }
        }
    }

    private suspend fun invokeOnChannel(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): ClientToolOutcome {
        val chatId = (functionCall.arguments["channelId"] as? String)
            ?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
            ?: return failed("client_context_missing", "channelId must be a UUID.")
        val chat = channelDeliveryService.resolveTarget(meta.userId, chatId)
            ?.takeIf { it.clientType in supportedClientTypes }
            ?: return failed("client_context_missing", "Device channel not found.")
        if (!eventService.hasLiveSubscriber(chat.userId, chat.id)) {
            return failed("client_context_missing", "No device is connected on that channel.")
        }
        val threadId = UUID.randomUUID()
        val context = ToolCallContext(meta.userId, chatId.toString(), threadId.toString(), UUID.randomUUID().toString())
        val deadlineAt = now().plus(timeout)
        return registry.withChannelTool(context, deadlineAt) { pending ->
            val published = eventService.publishClientToolCall(
                userId = meta.userId,
                chatId = chatId,
                executionId = threadId,
                payload = PublicToolCallStartedPayload(
                    toolCallId = context.toolCallId,
                    name = fn.name,
                    arguments = restJsonMapper.valueToTree(functionCall.arguments - "channelId"),
                    deadlineAt = deadlineAt.toString(),
                ),
            )
            if (!published) return@withChannelTool failed(
                "client_tool_busy", "Device command queues are full or disconnected.",
            )
            val sourceExecution = meta.requestId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            sourceExecution?.let { metrics?.executions?.beginClientWait(it) }.use {
                pending.awaitResult(now())
            }
        }
    }

    private fun outcomeMessage(functionName: String, outcome: ClientToolOutcome): LLMRequest.Message =
        LLMRequest.Message(
            role = LLMMessageRole.function,
            content = restJsonMapper.writeValueAsString(when (outcome.status) {
                "succeeded" -> outcome.result
                else -> mapOf(
                    "status" to outcome.status,
                    "error" to (outcome.error ?: ClientError("client_tool_failed", "Client tool failed.")),
                )
            }),
            name = functionName,
        )

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
        return completed ?: timeOut(context, threadId, toolCallId)
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
    ): ClientToolOutcome {
        val error = ClientError("client_tool_timed_out", "Client tool result deadline expired.")
        val payloadHash = PublicPayloadHash.ofValue(mapOf("status" to "timed_out", "error" to error))
        val completed = toolCallRepository.completeClientCall(
            context = context,
            status = ToolCallStatus.TIMED_OUT,
            resultJson = null,
            errorJson = restJsonMapper.writeValueAsString(error),
            payloadHash = payloadHash,
            receivedAt = now(),
        )
        val storedOutcome = if (completed == null) {
            toolCallRepository.get(context)
                ?.takeIf { it.target == "client" && it.status != ToolCallStatus.RUNNING }
                ?.toClientToolOutcome(restJsonMapper)
        } else null
        val outcome = storedOutcome ?: ClientToolOutcome("timed_out", null, error)
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

    private fun failed(code: String, message: String) = ClientToolOutcome("failed", null, ClientError(code, message))
}
