package ru.souz.backend.agent.runtime

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.events.model.AgentEventPayload
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.ChoiceOptionItemPayload
import ru.souz.backend.events.model.ChoiceRequestedPayload
import ru.souz.backend.events.model.ExecutionCancelledPayload
import ru.souz.backend.events.model.ExecutionFailedPayload
import ru.souz.backend.events.model.ExecutionFinishedPayload
import ru.souz.backend.events.model.ExecutionStartedPayload
import ru.souz.backend.events.model.ExecutionUsagePayload
import ru.souz.backend.events.model.MessageCompletedPayload
import ru.souz.backend.events.model.MessageCreatedPayload
import ru.souz.backend.events.model.MessageDeltaPayload
import ru.souz.backend.events.model.ToolCallFailedPayload
import ru.souz.backend.events.model.ToolCallFinishedPayload
import ru.souz.backend.events.model.ToolCallStartedPayload
import ru.souz.backend.events.model.PublicErrorPayload
import ru.souz.backend.events.model.ThreadCancelledPayload
import ru.souz.backend.events.model.ThreadCompletedPayload
import ru.souz.backend.events.model.ThreadFailedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionKind
import ru.souz.backend.options.model.OptionItem
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.toolcall.repository.ToolCallRepository

/**
 * Not logically concurrent.
 *
 * This sink keeps mutable per-execution state and expects events for one
 * execution to be processed in order. A Mutex is used as a defensive guard
 * against accidental concurrent emit calls.
 */
internal class BackendAgentRuntimeEventSink(
    private val userId: String,
    private val chatId: UUID,
    private val executionId: UUID,
    private val messageRepository: MessageRepository,
    private val optionRepository: OptionRepository,
    private val executionRepository: AgentExecutionRepository,
    private val eventService: AgentEventService,
    private val toolCallRepository: ToolCallRepository,
    private val streamingMessagesEnabled: Boolean,
    private val toolEventsEnabled: Boolean,
    private val optionsEnabled: Boolean = false,
    private val assistantMessageId: UUID? = null,
    private val toolCallPreviewer: ToolCallPreviewer = ToolCallPreviewer(),
    private val beforePublicEvent: suspend () -> Unit = {},
    private val publicClientThread: Boolean = false,
) : AgentRuntimeEventSink {
    private val emitMutex = Mutex()
    private val finalAssistantMessageId = assistantMessageId ?: UUID.randomUUID()
    private var assistantMessage: ChatMessage? = null
    private var requestedOptionId: UUID? = null

    val currentAssistantMessageId: UUID? get() = assistantMessage?.id
    val hasRequestedOption: Boolean get() = requestedOptionId != null

    override suspend fun emit(event: AgentRuntimeEvent) = emitMutex.withLock {
        handleEvent(event)
    }

    private suspend fun handleEvent(event: AgentRuntimeEvent) {
        when (event) {
            is AgentRuntimeEvent.MemoryPromptAugmented -> Unit
            is AgentRuntimeEvent.LlmMessageDelta -> onLlmMessageDelta(event)
            is AgentRuntimeEvent.ToolCallStarted -> onToolCallStarted(event)

            is AgentRuntimeEvent.ToolCallFinished -> onToolCallFinished(event)

            is AgentRuntimeEvent.ToolCallFailed -> onToolCallFailed(event)

            is AgentRuntimeEvent.ChoiceRequested -> if (optionsEnabled && !publicClientThread) {
                val option = persistOption(event)
                requestedOptionId = option.id
                executionRepository.get(userId, executionId)?.let { execution ->
                    executionRepository.update(
                        execution.copy(
                            status = AgentExecutionStatus.WAITING_OPTION,
                            assistantMessageId = execution.assistantMessageId ?: assistantMessage?.id,
                        )
                    )
                }
                appendDurableEvent(
                    type = AgentEventType.OPTION_REQUESTED,
                    payload = ChoiceRequestedPayload(
                        optionId = option.id,
                        kind = option.kind.value,
                        title = option.title,
                        selectionMode = option.selectionMode,
                        options = option.options.map { item ->
                            ChoiceOptionItemPayload(item.id, item.label, item.content)
                        },
                    ),
                )
            }
        }
    }

    suspend fun emitExecutionStarted(execution: AgentExecution) {
        if (publicClientThread) return
        appendDurableEvent(
            type = AgentEventType.EXECUTION_STARTED,
            payload = ExecutionStartedPayload(
                executionId = execution.id,
                userMessageId = execution.userMessageId,
                model = execution.model?.alias,
                provider = execution.provider?.name,
                streamingMessages = streamingMessagesEnabled,
            ),
        )
    }

    private suspend fun onToolCallStarted(event: AgentRuntimeEvent.ToolCallStarted) {
        val argumentsPreviewNode = toolCallPreviewer.argumentsPreview(event.arguments)
        val argumentsPreview = toolCallPreviewer.argumentsPreviewJson(event.arguments)
        toolCallRepository.started(
            context = toolCallContext(event.toolCallId.toString()),
            name = event.name,
            argumentsPreview = argumentsPreview,
        )
        if (!publicClientThread && toolEventsEnabled) {
            appendDurableEvent(
                type = AgentEventType.TOOL_CALL_STARTED,
                payload = ToolCallStartedPayload(
                    toolCallId = event.toolCallId,
                    name = event.name,
                    argumentKeys = event.arguments.keys.sorted(),
                    argumentsPreview = argumentsPreviewNode,
                ),
            )
        }
    }

    private suspend fun onToolCallFinished(event: AgentRuntimeEvent.ToolCallFinished) {
        val resultPreviewNode = toolCallPreviewer.resultPreview(event.result)
        val resultPreview = toolCallPreviewer.resultPreviewJson(event.result)
        toolCallRepository.finished(
            context = toolCallContext(event.toolCallId.toString()),
            name = event.name,
            resultPreview = resultPreview,
            durationMs = event.durationMs,
        )
        if (!publicClientThread && toolEventsEnabled) {
            appendDurableEvent(
                type = AgentEventType.TOOL_CALL_FINISHED,
                payload = ToolCallFinishedPayload(
                    toolCallId = event.toolCallId,
                    name = event.name,
                    resultPreview = resultPreviewNode,
                    durationMs = event.durationMs,
                ),
            )
        }
    }

    private suspend fun onToolCallFailed(event: AgentRuntimeEvent.ToolCallFailed) {
        val safeError = toolCallPreviewer.safeErrorPreview(event.error)
        toolCallRepository.failed(
            context = toolCallContext(event.toolCallId.toString()),
            name = event.name,
            error = safeError,
            durationMs = event.durationMs,
        )
        if (!publicClientThread && toolEventsEnabled) {
            appendDurableEvent(
                type = AgentEventType.TOOL_CALL_FAILED,
                payload = ToolCallFailedPayload(
                    toolCallId = event.toolCallId,
                    name = event.name,
                    error = safeError,
                    durationMs = event.durationMs,
                ),
            )
        }
    }

    suspend fun completeAssistantMessage(content: String): ChatMessage {
        val completedMessage = assistantMessage?.let { existing ->
            messageRepository.updateContent(
                userId = userId,
                chatId = chatId,
                messageId = existing.id,
                content = content,
            ) ?: existing.copy(content = content)
        } ?: loadExistingAssistantMessageIfPresent()?.let { existing ->
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
            id = finalAssistantMessageId,
        ).also { created ->
            assistantMessage = created
            emitMessageCreated(created)
            linkAssistantMessage(created.id)
        }

        assistantMessage = completedMessage
        if (!publicClientThread) {
            appendDurableEvent(
                type = AgentEventType.MESSAGE_COMPLETED,
                payload = MessageCompletedPayload(
                    messageId = completedMessage.id,
                    seq = completedMessage.seq,
                    role = completedMessage.role.value,
                    content = completedMessage.content,
                ),
            )
        }
        return completedMessage
    }

    suspend fun emitExecutionFinished(execution: AgentExecution) {
        if (publicClientThread) {
            beforePublicEvent()
            appendDurableEvent(
                type = AgentEventType.THREAD_COMPLETED,
                payload = ThreadCompletedPayload(response = assistantMessage?.content.orEmpty()),
            )
        } else {
            appendDurableEvent(
                type = AgentEventType.EXECUTION_FINISHED,
                payload = ExecutionFinishedPayload(
                    executionId = execution.id,
                    assistantMessageId = execution.assistantMessageId,
                    status = execution.status.value,
                    usage = execution.usage?.let { usage ->
                        ExecutionUsagePayload(
                            usage.promptTokens,
                            usage.completionTokens,
                            usage.totalTokens,
                            usage.precachedTokens,
                        )
                    },
                ),
            )
        }
    }

    suspend fun emitExecutionFailed(
        errorCode: String,
        errorMessage: String,
    ) {
        if (publicClientThread) {
            beforePublicEvent()
            appendDurableEvent(
                type = AgentEventType.THREAD_FAILED,
                payload = ThreadFailedPayload(PublicErrorPayload(errorCode.toPublicErrorCode(), errorMessage)),
            )
        } else {
            appendDurableEvent(
                type = AgentEventType.EXECUTION_FAILED,
                payload = ExecutionFailedPayload(executionId, assistantMessage?.id, errorCode, errorMessage),
            )
        }
    }

    suspend fun emitExecutionCancelled() {
        if (publicClientThread) {
            beforePublicEvent()
            appendDurableEvent(type = AgentEventType.THREAD_CANCELLED, payload = ThreadCancelledPayload())
        } else {
            appendDurableEvent(
                type = AgentEventType.EXECUTION_CANCELLED,
                payload = ExecutionCancelledPayload(executionId, assistantMessage?.id),
            )
        }
    }

    private suspend fun onLlmMessageDelta(event: AgentRuntimeEvent.LlmMessageDelta) {
        if (publicClientThread || !streamingMessagesEnabled || event.text.isEmpty()) return
        publishLiveEvent(
            type = AgentEventType.MESSAGE_DELTA,
            payload = MessageDeltaPayload(finalAssistantMessageId, event.text),
        )
    }

    suspend fun emitMessageCreated(message: ChatMessage) {
        if (publicClientThread) return
        appendDurableEvent(
            type = AgentEventType.MESSAGE_CREATED,
            payload = MessageCreatedPayload(
                messageId = message.id,
                seq = message.seq,
                role = message.role.value,
                content = message.content,
                clientMessageId = message.metadata["clientMessageId"]?.takeIf { it.isNotEmpty() },
            ),
        )
    }

    private fun toolCallContext(toolCallId: String): ToolCallContext =
        ToolCallContext(
            userId = userId,
            chatId = chatId.toString(),
            executionId = executionId.toString(),
            toolCallId = toolCallId,
        )

    private suspend fun loadExistingAssistantMessageIfPresent(): ChatMessage? {
        if (assistantMessage != null || assistantMessageId == null) {
            return assistantMessage
        }
        val existing = messageRepository.getById(
            userId = userId,
            chatId = chatId,
            messageId = assistantMessageId,
        ) ?: return null
        assistantMessage = existing
        return existing
    }

    private suspend fun linkAssistantMessage(messageId: UUID) {
        val execution = executionRepository.get(userId, executionId) ?: return
        if (execution.assistantMessageId == messageId) {
            return
        }
        executionRepository.update(execution.copy(assistantMessageId = messageId))
    }

    private suspend fun persistOption(event: AgentRuntimeEvent.ChoiceRequested): Option {
        val option = Option(
            id = UUID.fromString(event.choiceId),
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            kind = OptionKind.entries.firstOrNull { it.value == event.kind }
                ?: error("Unsupported option kind: ${event.kind}"),
            title = event.title,
            selectionMode = event.selectionMode,
            options = event.options.map { option ->
                OptionItem(
                    id = option.id,
                    label = option.label,
                    content = option.content,
                )
            },
            payload = emptyMap(),
            status = OptionStatus.PENDING,
            answer = null,
            createdAt = Instant.now(),
            expiresAt = null,
            answeredAt = null,
        )
        return optionRepository.save(option)
    }

    private suspend fun appendDurableEvent(
        type: AgentEventType,
        payload: AgentEventPayload,
    ) {
        eventService.appendDurable(
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            type = type,
            payload = payload,
        )
    }

    private suspend fun publishLiveEvent(
        type: AgentEventType,
        payload: AgentEventPayload,
    ) {
        eventService.publishLive(
            userId = userId,
            chatId = chatId,
            executionId = executionId,
            type = type,
            payload = payload,
        )
    }

}

private fun String.toPublicErrorCode(): String =
    if (this in publicErrorCodes) this else "internal_error"

private val publicErrorCodes = setOf(
    "invalid_request",
    "chat_not_found",
    "thread_not_found",
    "thread_already_terminal",
    "tool_call_not_found",
    "idempotency_conflict",
    "feature_disabled",
    "message_rejected",
    "internal_error",
)
