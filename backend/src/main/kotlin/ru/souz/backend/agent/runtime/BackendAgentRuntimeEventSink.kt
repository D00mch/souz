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
            is AgentRuntimeEvent.LlmMessageDelta -> onLlmMessageDelta(event)
            is AgentRuntimeEvent.ToolCallStarted -> onToolCallStarted(event)

            is AgentRuntimeEvent.ToolCallFinished -> onToolCallFinished(event)

            is AgentRuntimeEvent.ToolCallFailed -> onToolCallFailed(event)

            is AgentRuntimeEvent.ChoiceRequested -> if (optionsEnabled) {
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
                            ChoiceOptionItemPayload(
                                id = item.id,
                                label = item.label,
                                content = item.content,
                            )
                        },
                    ),
                )
            }
        }
    }

    suspend fun emitExecutionStarted(execution: AgentExecution) {
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
        if (!toolEventsEnabled) {
            return
        }
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

    private suspend fun onToolCallFinished(event: AgentRuntimeEvent.ToolCallFinished) {
        val resultPreviewNode = toolCallPreviewer.resultPreview(event.result)
        val resultPreview = toolCallPreviewer.resultPreviewJson(event.result)
        toolCallRepository.finished(
            context = toolCallContext(event.toolCallId.toString()),
            name = event.name,
            resultPreview = resultPreview,
            durationMs = event.durationMs,
        )
        if (!toolEventsEnabled) {
            return
        }
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

    private suspend fun onToolCallFailed(event: AgentRuntimeEvent.ToolCallFailed) {
        val safeError = toolCallPreviewer.safeErrorPreview(event.error)
        toolCallRepository.failed(
            context = toolCallContext(event.toolCallId.toString()),
            name = event.name,
            error = safeError,
            durationMs = event.durationMs,
        )
        if (!toolEventsEnabled) {
            return
        }
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
        appendDurableEvent(
            type = AgentEventType.MESSAGE_COMPLETED,
            payload = MessageCompletedPayload(
                messageId = completedMessage.id,
                seq = completedMessage.seq,
                role = completedMessage.role.value,
                content = completedMessage.content,
            ),
        )
        return completedMessage
    }

    suspend fun emitExecutionFinished(execution: AgentExecution) {
        appendDurableEvent(
            type = AgentEventType.EXECUTION_FINISHED,
            payload = ExecutionFinishedPayload(
                executionId = execution.id,
                assistantMessageId = execution.assistantMessageId,
                status = execution.status.value,
                usage = execution.usage?.let { usage ->
                    ExecutionUsagePayload(
                        promptTokens = usage.promptTokens,
                        completionTokens = usage.completionTokens,
                        totalTokens = usage.totalTokens,
                        precachedTokens = usage.precachedTokens,
                    )
                },
            ),
        )
    }

    suspend fun emitExecutionFailed(
        errorCode: String,
        errorMessage: String,
    ) {
        appendDurableEvent(
            type = AgentEventType.EXECUTION_FAILED,
            payload = ExecutionFailedPayload(
                executionId = executionId,
                assistantMessageId = assistantMessage?.id,
                errorCode = errorCode,
                errorMessage = errorMessage,
            ),
        )
    }

    suspend fun emitExecutionCancelled() {
        appendDurableEvent(
            type = AgentEventType.EXECUTION_CANCELLED,
            payload = ExecutionCancelledPayload(
                executionId = executionId,
                assistantMessageId = assistantMessage?.id,
            ),
        )
    }

    private suspend fun onLlmMessageDelta(event: AgentRuntimeEvent.LlmMessageDelta) {
        if (!streamingMessagesEnabled || event.text.isEmpty()) return

        publishLiveEvent(
            type = AgentEventType.MESSAGE_DELTA,
            payload = MessageDeltaPayload(
                messageId = finalAssistantMessageId,
                delta = event.text,
            ),
        )
    }

    suspend fun emitMessageCreated(message: ChatMessage) {
        appendDurableEvent(
            type = AgentEventType.MESSAGE_CREATED,
            payload = MessageCreatedPayload(
                messageId = message.id,
                seq = message.seq,
                role = message.role.value,
                content = message.content,
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
