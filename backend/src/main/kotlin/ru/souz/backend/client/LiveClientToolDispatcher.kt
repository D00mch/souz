package ru.souz.backend.client

import java.time.Duration
import java.time.Instant
import java.util.UUID
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.llms.restJsonMapper

/** Shared live RPC for cross-channel client Skills and personality sync; no durable events or tool-call rows. */
internal class LiveClientToolDispatcher(
    private val registry: ClientThreadRuntimeRegistry,
    private val events: AgentEventService,
    private val now: () -> Instant = Instant::now,
    private val metrics: BackendMetrics? = null,
) {
    suspend fun call(
        chat: Chat,
        name: String,
        arguments: Map<String, Any>,
        timeout: Duration,
        sourceExecution: UUID? = null,
        sourceThreadId: UUID? = null,
    ): ClientToolOutcome {
        val threadId = sourceThreadId ?: UUID.randomUUID()
        val context = ToolCallContext(chat.userId, chat.id.toString(), threadId.toString(), UUID.randomUUID().toString())
        val deadline = now().plus(timeout)
        return registry.withChannelTool(context, deadline) { pending ->
            val published = events.publishClientToolCall(
                userId = chat.userId, chatId = chat.id, executionId = threadId,
                payload = PublicToolCallStartedPayload(
                    toolCallId = context.toolCallId, name = name,
                    arguments = restJsonMapper.valueToTree(arguments), deadlineAt = deadline.toString(),
                ),
            )
            if (!published) return@withChannelTool ClientToolOutcome(
                "failed", null, ClientError("client_tool_busy", "Device command queues are full or disconnected."),
            )
            sourceExecution?.let { metrics?.executions?.beginClientWait(it) }.use {
                pending.awaitResult(now())
            }
        }
    }
}
