package ru.souz.backend.client

import java.time.Duration
import java.time.Instant
import java.util.UUID
import ru.souz.backend.channels.ChannelDeliveryService
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.events.model.PublicToolCallStartedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.llms.restJsonMapper

/** Transient channel RPC. Arguments and results never enter durable events or tool-call storage. */
internal class LiveClientToolDispatcher(
    private val registry: ClientThreadRuntimeRegistry,
    private val events: AgentEventService,
    private val channels: ChannelDeliveryService,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun resolve(userId: String, chatId: UUID, types: Set<String>): Chat? =
        channels.resolveTarget(userId, chatId)?.takeIf {
            it.clientType in types && events.hasLiveSubscriber(userId, chatId)
        }

    suspend fun schedulerChannel(userId: String, explicit: UUID?, preferred: UUID?): Chat? {
        val types = setOf("backend")
        if (explicit != null) return resolve(userId, explicit, types)
        if (preferred != null) resolve(userId, preferred, types)?.let { return it }
        return channels.resolveTargets(userId, events.liveChatIds(userId)).values
            .firstOrNull { it.clientType in types && events.hasLiveSubscriber(userId, it.id) }
    }

    suspend fun call(chat: Chat, name: String, arguments: Map<String, Any>, timeout: Duration): ClientToolOutcome {
        val threadId = UUID.randomUUID()
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
            pending.awaitResult(now())
        }
    }
}
