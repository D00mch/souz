package ru.souz.backend.agent.runtime

import java.util.UUID
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.llms.restJsonMapper

internal class BackendAgentRuntimeEventSink(
    private val userId: String,
    private val chatId: UUID,
    private val executionId: UUID,
    private val messageRepository: MessageRepository,
    private val eventService: AgentEventService,
    private val streamingMessagesEnabled: Boolean,
    private val toolEventsEnabled: Boolean,
    private val choicesEnabled: Boolean = false,
    private val assistantMessageId: UUID = UUID.randomUUID(),
) : AgentRuntimeEventSink {
    private val accumulatedAssistantContent = StringBuilder()
    private var assistantMessage: ChatMessage? = null

    val currentAssistantMessageId: UUID? get() = assistantMessage?.id

    override suspend fun emit(event: AgentRuntimeEvent) {
        when (event) {
            is AgentRuntimeEvent.LlmMessageDelta -> onLlmMessageDelta(event)
            is AgentRuntimeEvent.ToolCallStarted -> if (toolEventsEnabled) {
                appendEvent(
                    type = AgentEventType.TOOL_CALL_STARTED,
                    payload = mapOf(
                        "toolCallId" to event.toolCallId,
                        "name" to event.name,
                        "arguments" to restJsonMapper.writeValueAsString(event.arguments),
                    ),
                )
            }

            is AgentRuntimeEvent.ToolCallFinished -> if (toolEventsEnabled) {
                appendEvent(
                    type = AgentEventType.TOOL_CALL_FINISHED,
                    payload = mapOf(
                        "toolCallId" to event.toolCallId,
                        "name" to event.name,
                        "resultPreview" to (event.resultPreview ?: ""),
                        "durationMs" to event.durationMs.toString(),
                    ),
                )
            }

            is AgentRuntimeEvent.ToolCallFailed -> if (toolEventsEnabled) {
                appendEvent(
                    type = AgentEventType.TOOL_CALL_FAILED,
                    payload = mapOf(
                        "toolCallId" to event.toolCallId,
                        "name" to event.name,
                        "error" to event.error,
                        "durationMs" to event.durationMs.toString(),
                    ),
                )
            }

            is AgentRuntimeEvent.ChoiceRequested -> if (choicesEnabled) {
                appendEvent(
                    type = AgentEventType.CHOICE_REQUESTED,
                    payload = mapOf(
                        "choiceId" to event.choiceId,
                        "kind" to event.kind,
                        "title" to (event.title ?: ""),
                        "selectionMode" to event.selectionMode,
                        "options" to restJsonMapper.writeValueAsString(event.options),
                    ),
                )
            }
        }
    }

    suspend fun emitExecutionStarted(execution: AgentExecution) {
        appendEvent(
            type = AgentEventType.EXECUTION_STARTED,
            payload = buildMap {
                put("executionId", execution.id.toString())
                execution.userMessageId?.let { put("userMessageId", it.toString()) }
                execution.model?.let { put("model", it.alias) }
                execution.provider?.let { put("provider", it.name) }
                put("streamingMessages", streamingMessagesEnabled.toString())
            },
        )
    }

    suspend fun completeAssistantMessage(content: String): ChatMessage {
        val completedMessage = assistantMessage?.let { existing ->
            messageRepository.updateContent(
                userId = userId,
                chatId = chatId,
                messageId = existing.id,
                content = content,
            ) ?: existing.copy(content = content)
        } ?: messageRepository.append(
            userId = userId,
            chatId = chatId,
            role = ChatRole.ASSISTANT,
            content = content,
            id = assistantMessageId,
        ).also { created ->
            assistantMessage = created
            emitMessageCreated(created)
        }

        assistantMessage = completedMessage
        appendEvent(
            type = AgentEventType.MESSAGE_COMPLETED,
            payload = messagePayload(completedMessage),
        )
        return completedMessage
    }

    suspend fun emitExecutionFinished(execution: AgentExecution) {
        appendEvent(
            type = AgentEventType.EXECUTION_FINISHED,
            payload = buildMap {
                put("executionId", execution.id.toString())
                execution.assistantMessageId?.let { put("assistantMessageId", it.toString()) }
                put("status", execution.status.value)
                execution.usage?.let { usage ->
                    put("promptTokens", usage.promptTokens.toString())
                    put("completionTokens", usage.completionTokens.toString())
                    put("totalTokens", usage.totalTokens.toString())
                    put("precachedTokens", usage.precachedTokens.toString())
                }
            },
        )
    }

    suspend fun emitExecutionFailed(
        errorCode: String,
        errorMessage: String,
    ) {
        appendEvent(
            type = AgentEventType.EXECUTION_FAILED,
            payload = buildMap {
                put("executionId", executionId.toString())
                put("errorCode", errorCode)
                put("errorMessage", errorMessage)
                assistantMessage?.id?.let { put("assistantMessageId", it.toString()) }
            },
        )
    }

    suspend fun emitExecutionCancelled() {
        appendEvent(
            type = AgentEventType.EXECUTION_CANCELLED,
            payload = buildMap {
                put("executionId", executionId.toString())
                assistantMessage?.id?.let { put("assistantMessageId", it.toString()) }
            },
        )
    }

    private suspend fun onLlmMessageDelta(event: AgentRuntimeEvent.LlmMessageDelta) {
        if (!streamingMessagesEnabled || event.text.isEmpty()) return

        accumulatedAssistantContent.append(event.text)
        val message = ensureAssistantMessageCreated()
        assistantMessage = messageRepository.updateContent(
            userId = userId,
            chatId = chatId,
            messageId = message.id,
            content = accumulatedAssistantContent.toString(),
        ) ?: message

        appendEvent(
            type = AgentEventType.MESSAGE_DELTA,
            payload = buildMap {
                put("messageId", message.id.toString())
                put("seq", message.seq.toString())
                put("delta", event.text)
            },
        )
    }

    private suspend fun ensureAssistantMessageCreated(): ChatMessage =
        assistantMessage ?: messageRepository.append(
            userId = userId,
            chatId = chatId,
            role = ChatRole.ASSISTANT,
            content = "",
            id = assistantMessageId,
        ).also { created ->
            assistantMessage = created
            emitMessageCreated(created)
        }

    private suspend fun emitMessageCreated(message: ChatMessage) {
        appendEvent(
            type = AgentEventType.MESSAGE_CREATED,
            payload = messagePayload(message),
        )
    }

    private suspend fun appendEvent(
        type: AgentEventType,
        payload: Map<String, String>,
    ) {
        eventService.append(
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            type = type,
            payload = payload,
        )
    }

    private fun messagePayload(message: ChatMessage): Map<String, String> = buildMap {
        put("messageId", message.id.toString())
        put("seq", message.seq.toString())
        put("role", message.role.value)
        put("content", message.content)
    }
}
