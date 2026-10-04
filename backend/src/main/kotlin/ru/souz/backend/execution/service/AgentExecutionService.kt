package ru.souz.backend.execution.service

import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.hooks.HookConfig
import ru.souz.backend.hooks.HookStore
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.AssistantMessagePayload
import ru.souz.backend.events.model.ChoiceAnsweredPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.toolcall.repository.ToolCallRepository

data class CancelExecutionResult(
    val execution: AgentExecution,
)

class AgentExecutionService internal constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val executionRepository: AgentExecutionRepository,
    private val optionRepository: OptionRepository,
    private val eventService: AgentEventService,
    private val toolCallRepository: ToolCallRepository,
    private val requestFactory: AgentExecutionRequestFactory,
    private val finalizer: AgentExecutionFinalizer,
    private val launcher: AgentExecutionLauncher,
    private val optionsEnabled: Boolean,
    private val hookStore: HookStore,
    private val hookConfig: HookConfig,
) {
    private val logger = LoggerFactory.getLogger(AgentExecutionService::class.java)
    private val hookSlots = Semaphore(hookConfig.concurrentExecutions)

    suspend fun executeChatTurn(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String? = null,
        requestOverrides: UserSettingsOverrides = UserSettingsOverrides(),
        executionId: UUID = UUID.randomUUID(),
        latestDeviceContextJson: String = "{}",
        userMessageMetadata: Map<String, String> = emptyMap(),
        clientToolsEnabled: Boolean = false,
    ): SendMessageResult {
        val prepared = prepareChatTurn(
            userId = userId,
            chatId = chatId,
            content = content,
            clientMessageId = clientMessageId,
            requestOverrides = requestOverrides,
            executionId = executionId,
            latestDeviceContextJson = latestDeviceContextJson,
            userMessageMetadata = userMessageMetadata,
            clientToolsEnabled = clientToolsEnabled,
        )
        prepared.normalizedClientMessageId?.let { normalizedClientMessageId ->
            executionRepository.findByClientMessageId(userId, chatId, normalizedClientMessageId)
                ?.let { existingExecution ->
                    val userMessageId = existingExecution.userMessageId
                    val userMessage = userMessageId?.let { messageRepository.getById(userId, chatId, it) }
                    if (userMessage != null) {
                        val assistantMessage = existingExecution.assistantMessageId
                            ?.let { messageRepository.getById(userId, chatId, it) }
                        return SendMessageResult(userMessage, assistantMessage, existingExecution)
                    }
                }
        }
        try {
            executionRepository.create(prepared.execution)
        } catch (e: ActiveAgentExecutionConflictException) {
            throw BackendV1Exception(
                status = HttpStatusCode.Conflict,
                code = "chat_already_has_active_execution",
                message = "Chat already has an active execution.",
            )
        }
        return try {
            startPreparedChatTurn(prepared)
        } catch (e: BackendV1Exception) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finalizer.markFailed(
                execution = prepared.execution,
                errorCode = "agent_execution_failed",
                errorMessage = e.message ?: "Agent execution failed.",
                usage = prepared.execution.usage,
            )
            throw BackendV1Exception(
                status = HttpStatusCode.InternalServerError,
                code = "agent_execution_failed",
                message = "Agent execution failed.",
            )
        }
    }

    internal suspend fun prepareChatTurn(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String? = null,
        requestOverrides: UserSettingsOverrides = UserSettingsOverrides(),
        executionId: UUID = UUID.randomUUID(),
        latestDeviceContextJson: String = "{}",
        userMessageMetadata: Map<String, String> = emptyMap(),
        clientToolsEnabled: Boolean = false,
    ): PreparedChatTurn {
        requireOwnedChat(userId, chatId)
        return requestFactory.prepareChatTurn(
            userId = userId,
            chatId = chatId,
            content = content,
            clientMessageId = clientMessageId,
            requestOverrides = requestOverrides,
            executionId = executionId,
            latestDeviceContextJson = latestDeviceContextJson,
            userMessageMetadataExtras = userMessageMetadata,
            clientToolsEnabled = clientToolsEnabled,
        )
    }

    internal suspend fun startPreparedChatTurn(prepared: PreparedChatTurn): SendMessageResult {
        val queuedExecution = prepared.execution
        val userId = queuedExecution.userId
        val chatId = queuedExecution.chatId
        val userMessage = messageRepository.append(
            userId = userId,
            chatId = chatId,
            role = ChatRole.USER,
            content = prepared.runtimeRequest.prompt,
            metadata = prepared.userMessageMetadata,
        )
        chatRepository.touchUpdatedAt(userId, chatId, userMessage.createdAt)

        val runningExecution = executionRepository.start(queuedExecution, userMessage.id) ?: return SendMessageResult(
            userMessage = userMessage,
            assistantMessage = null,
            execution = executionRepository.getByChat(userId, chatId, queuedExecution.id) ?: queuedExecution,
        )
        val eventSink = createEventSink(runningExecution, prepared.effectiveSettings.streamingMessages, prepared.effectiveSettings.showToolEvents)
        eventSink.emitMessageCreated(userMessage)
        eventSink.emitExecutionStarted(runningExecution)

        launchExecution(
            execution = runningExecution,
            conversationKey = prepared.conversationKey,
            turnRequest = prepared.runtimeRequest.copy(inputMessageSeq = userMessage.seq),
            eventSink = eventSink,
        )

        return SendMessageResult(
            userMessage = userMessage,
            assistantMessage = null,
            execution = runningExecution,
        )
    }

    suspend fun executeChatTurnAndAwaitCompletion(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String? = null,
        requestOverrides: UserSettingsOverrides = UserSettingsOverrides(),
        onProgress: suspend (String) -> Unit,
    ): SendMessageResult = coroutineScope {
        // Subscribe before execution can publish its first block; no replay or device commands.
        val subscription = eventService.observeLive(userId, chatId)
        var sender: Job? = null
        try {
            val started = executeChatTurn(userId, chatId, content, clientMessageId, requestOverrides)
            sender = launch {
                for (event in subscription.events) {
                    if (event.executionId != started.execution.id) continue
                    val progress = event.payload as? AssistantMessagePayload ?: continue
                    try {
                        onProgress(progress.content)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        logger.warn("Progress delivery failed for execution {}", started.execution.id)
                    }
                }
            }
            launcher.join(started.execution.id)
            val finished = executionRepository.getByChat(userId, chatId, started.execution.id) ?: started.execution
            started.copy(
                assistantMessage = finished.assistantMessageId?.let { messageRepository.getById(userId, chatId, it) },
                execution = finished,
            )
        } finally {
            // Stop even a blocked send before the caller delivers the final reply.
            withContext(NonCancellable) {
                sender?.cancelAndJoin()
                subscription.close()
            }
        }
    }

    private suspend fun launchExecution(
        execution: AgentExecution,
        conversationKey: AgentConversationKey,
        turnRequest: BackendConversationTurnRequest,
        eventSink: BackendAgentRuntimeEventSink,
    ) {
        val isHook = hookStore.find(execution.userId, execution.id) != null
        launcher.launchRegistered(
            execution = execution,
            onCancelled = {
                finalizer.finalizeCancelledExecutionIfNeeded(
                    execution = execution,
                    eventSink = eventSink,
                )
            },
        ) {
            try {
                val run = suspend {
                    finalizer.runExecution(execution, conversationKey, turnRequest, eventSink)
                }
                // Both initial turns and option continuations acquire capacity for actual agent work.
                if (isHook) hookSlots.withPermit { withTimeout(hookConfig.executionTimeoutMillis) { run() } } else run()
            } catch (_: BackendV1Exception) {
                // Background failures are already persisted by AgentExecutionFinalizer.
            }
        }
    }

    suspend fun resumeOption(option: Option): AgentExecution {
        launcher.join(option.executionId)
        val currentExecution = finalizer.currentExecution(option.executionId, option.userId, option.chatId)
        if (currentExecution.status != AgentExecutionStatus.WAITING_OPTION) {
            throw invalidV1Request("Execution is not waiting for an option.")
        }
        requireOwnedChat(option.userId, option.chatId)
        val runningExecution = executionRepository.transitionIfCurrent(currentExecution, AgentExecutionStatus.RUNNING)
            ?: throw invalidV1Request("Execution is not waiting for an option.")
        eventService.appendDurable(
            userId = option.userId,
            chatId = option.chatId,
            executionId = runningExecution.id,
            type = AgentEventType.OPTION_ANSWERED,
            payload = ChoiceAnsweredPayload(
                optionId = option.id,
                status = option.status.value,
                selectedOptionIds = option.answer?.selectedOptionIds?.toList().orEmpty(),
                freeText = option.answer?.freeText,
                metadata = option.answer?.metadata.orEmpty(),
            ),
        )

        val prepared = requestFactory.prepareContinuationTurn(runningExecution, option)
        val eventSink = createEventSink(runningExecution, prepared.streamingMessagesEnabled, prepared.toolEventsEnabled)
        launchExecution(
            execution = runningExecution,
            conversationKey = prepared.conversationKey,
            turnRequest = prepared.runtimeRequest,
            eventSink = eventSink,
        )
        return runningExecution
    }

    suspend fun cancelActive(
        userId: String,
        chatId: UUID,
    ): CancelExecutionResult {
        requireOwnedChat(userId, chatId)
        val activeExecution = executionRepository.findActive(userId, chatId)
            ?: throw invalidV1Request("Chat has no active execution.")
        return CancelExecutionResult(cancelExecutionInternal(activeExecution))
    }

    suspend fun cancelExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
    ): CancelExecutionResult {
        requireOwnedChat(userId, chatId)
        val execution = executionRepository.getByChat(userId, chatId, executionId)
            ?: throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "execution_not_found",
                message = "Execution not found.",
            )
        return CancelExecutionResult(cancelExecutionInternal(execution))
    }

    internal suspend fun finalizeInterruptedExecution(started: AgentExecution): AgentExecution? {
        val execution = executionRepository.getByChat(started.userId, started.chatId, started.id) ?: return null
        if (execution.status == AgentExecutionStatus.WAITING_OPTION) return execution
        val finished = if (!execution.status.isActive()) execution else finalizer.markFailed(
            execution = started,
            errorCode = "agent_execution_failed",
            errorMessage = "Agent execution was interrupted.",
            usage = execution.usage,
        )
        val sink = createEventSink(finished)
        sink.emitTerminal(finished)
        return finished
    }

    private fun createEventSink(execution: AgentExecution, streaming: Boolean = false, toolEvents: Boolean = false) =
        BackendAgentRuntimeEventSink(
            userId = execution.userId, chatId = execution.chatId, executionId = execution.id,
            messageRepository = messageRepository, optionRepository = optionRepository,
            eventService = eventService, toolCallRepository = toolCallRepository,
            streamingMessagesEnabled = streaming, toolEventsEnabled = toolEvents, optionsEnabled = optionsEnabled,
            assistantMessageId = execution.assistantMessageId,
            publicClientThread = execution.runtimeOwner != null,
            narrateSteps = execution.metadata[METADATA_NARRATE_STEPS] == "true",
        )

    private suspend fun cancelExecutionInternal(execution: AgentExecution): AgentExecution = withContext(NonCancellable) {
        val cancelling = finalizer.transition(execution, AgentExecutionStatus.CANCELLING, usage = null)
        if (!cancelling.status.isActive()) throw invalidV1Request("Execution is not active.")
        propagateCancellation(cancelling)
    }

    internal suspend fun propagateCancellation(execution: AgentExecution): AgentExecution =
        if (launcher.cancel(execution.id)) execution else
            finalizer.finalizeCancelledExecutionIfNeeded(execution, createEventSink(execution)) ?: execution

    private suspend fun requireOwnedChat(userId: String, chatId: UUID): Chat =
        chatRepository.get(userId, chatId)
            ?: throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "chat_not_found",
                message = "Chat not found.",
            )
}
