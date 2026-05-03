package ru.souz.backend.execution.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.supervisorScope
import ru.souz.backend.agent.model.BackendAgentTurn
import ru.souz.backend.agent.model.BackendAgentTurnInput
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.ChoiceAnsweredPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionRuntimeConfig
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.restJsonMapper

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
    private val effectiveSettingsResolver: EffectiveSettingsResolver?,
    private val featureFlags: BackendFeatureFlags?,
    private val runner: AgentExecutionRunner?,
    private val launcher: AgentExecutionLauncher,
    private val legacyRequestFactory: AgentExecutionRequestFactory? = null,
    private val legacyFinalizer: AgentExecutionFinalizer? = null,
) {
    @Deprecated("Production execution should use EffectiveSettingsResolver + BackendAgentTurn + AgentExecutionRunner.")
    internal constructor(
        chatRepository: ChatRepository,
        messageRepository: MessageRepository,
        executionRepository: AgentExecutionRepository,
        optionRepository: OptionRepository,
        eventService: AgentEventService,
        toolCallRepository: ToolCallRepository,
        requestFactory: AgentExecutionRequestFactory,
        finalizer: AgentExecutionFinalizer,
        launcher: AgentExecutionLauncher,
    ) : this(
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        executionRepository = executionRepository,
        optionRepository = optionRepository,
        eventService = eventService,
        toolCallRepository = toolCallRepository,
        effectiveSettingsResolver = null,
        featureFlags = null,
        runner = null,
        launcher = launcher,
        legacyRequestFactory = requestFactory,
        legacyFinalizer = finalizer,
    )

    suspend fun executeChatTurn(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String? = null,
        requestOverrides: UserSettingsOverrides = UserSettingsOverrides(),
    ): SendMessageResult = supervisorScope {
        legacyRequestFactory?.let { factory ->
            return@supervisorScope executeChatTurnLegacy(
                factory = factory,
                finalizer = requireNotNull(legacyFinalizer),
                userId = userId,
                chatId = chatId,
                content = content,
                clientMessageId = clientMessageId,
                requestOverrides = requestOverrides,
            )
        }

        val chat = requireOwnedChat(userId, chatId)
        val effectiveSettings = requireNotNull(effectiveSettingsResolver).resolve(userId, requestOverrides)
        val config = AgentExecutionRuntimeConfig.from(effectiveSettings)
        val normalizedClientMessageId = clientMessageId?.trim()?.takeIf { it.isNotEmpty() }
        val queuedExecution = try {
            executionRepository.create(
                AgentExecution(
                    id = UUID.randomUUID(),
                    userId = userId,
                    chatId = chatId,
                    userMessageId = null,
                    assistantMessageId = null,
                    status = AgentExecutionStatus.QUEUED,
                    requestId = null,
                    clientMessageId = normalizedClientMessageId,
                    model = effectiveSettings.defaultModel,
                    provider = effectiveSettings.defaultModel.provider,
                    startedAt = Instant.now(),
                    finishedAt = null,
                    cancelRequested = false,
                    errorCode = null,
                    errorMessage = null,
                    usage = null,
                    metadata = config.toMetadata(),
                )
            )
        } catch (e: ActiveAgentExecutionConflictException) {
            throw BackendV1Exception(
                status = HttpStatusCode.Conflict,
                code = "chat_already_has_active_execution",
                message = "Chat already has an active execution.",
            )
        }

        try {
            val userMessage = messageRepository.append(
                userId = userId,
                chatId = chatId,
                role = ChatRole.USER,
                content = content,
                metadata = userMessageMetadata(normalizedClientMessageId),
            )
            chatRepository.update(chat.copy(updatedAt = userMessage.createdAt))

            val runningExecution = executionRepository.update(
                queuedExecution.copy(
                    userMessageId = userMessage.id,
                    status = AgentExecutionStatus.RUNNING,
                    startedAt = Instant.now(),
                )
            )
            val turn = BackendAgentTurn(
                userId = userId,
                chatId = chatId,
                executionId = runningExecution.id,
                conversationId = chatId.toString(),
                input = BackendAgentTurnInput.UserMessage(content),
                config = config,
            )
            val eventSink = createEventSink(userId, chatId, runningExecution, config)
            eventSink.emitExecutionStarted(runningExecution)

            if (config.streamingMessages && requireNotNull(featureFlags).wsEvents) {
                launcher.startBackgroundExecution(runningExecution, eventSink) {
                    requireNotNull(runner).run(chat, runningExecution, turn, eventSink)
                }
                return@supervisorScope SendMessageResult(userMessage, null, runningExecution)
            }

            val executionResult = try {
                launcher.runTrackedExecution(runningExecution, eventSink) {
                    requireNotNull(runner).run(chat, runningExecution, turn, eventSink)
                }
            } catch (_: ExecutionCancelledException) {
                throw BackendV1Exception(
                    status = HttpStatusCode.Conflict,
                    code = "agent_execution_cancelled",
                    message = "Agent execution was cancelled.",
                )
            }

            SendMessageResult(
                userMessage = userMessage,
                assistantMessage = executionResult.assistantMessage,
                execution = executionResult.execution,
            )
        } catch (e: BackendV1Exception) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            requireNotNull(runner).markFailed(
                executionId = queuedExecution.id,
                userId = userId,
                chatId = chatId,
                errorCode = "agent_execution_failed",
                errorMessage = e.message ?: "Agent execution failed.",
                usage = queuedExecution.usage,
            )
            throw BackendV1Exception(
                status = HttpStatusCode.InternalServerError,
                code = "agent_execution_failed",
                message = "Agent execution failed.",
            )
        }
    }

    suspend fun resumeOption(option: Option): AgentExecution {
        legacyRequestFactory?.let { factory ->
            return resumeOptionLegacy(factory, requireNotNull(legacyFinalizer), option)
        }

        val currentExecution = requireNotNull(runner).currentExecution(option.executionId, option.userId, option.chatId)
        if (currentExecution.status != AgentExecutionStatus.WAITING_OPTION) {
            throw invalidV1Request("Execution is not waiting for an option.")
        }
        val chat = requireOwnedChat(option.userId, option.chatId)
        val runningExecution = executionRepository.update(
            currentExecution.copy(
                status = AgentExecutionStatus.RUNNING,
                finishedAt = null,
                cancelRequested = false,
                errorCode = null,
                errorMessage = null,
            )
        )
        appendOptionAnswered(option, runningExecution.id)

        val config = AgentExecutionRuntimeConfig.fromExecution(runningExecution)
        val turn = BackendAgentTurn(
            userId = option.userId,
            chatId = option.chatId,
            executionId = runningExecution.id,
            conversationId = option.chatId.toString(),
            input = BackendAgentTurnInput.OptionAnswer(
                optionId = option.id,
                payload = option.toContinuationPayload(),
            ),
            config = config,
        )
        val eventSink = createEventSink(option.userId, option.chatId, runningExecution, config)
        launcher.startBackgroundExecution(runningExecution, eventSink) {
            requireNotNull(runner).run(chat, runningExecution, turn, eventSink)
        }
        return runningExecution
    }

    suspend fun cancelActive(userId: String, chatId: UUID): CancelExecutionResult {
        requireOwnedChat(userId, chatId)
        val activeExecution = executionRepository.findActive(userId, chatId)
            ?: throw invalidV1Request("Chat has no active execution.")
        return CancelExecutionResult(cancelExecutionInternal(activeExecution))
    }

    suspend fun cancelExecution(userId: String, chatId: UUID, executionId: UUID): CancelExecutionResult {
        requireOwnedChat(userId, chatId)
        val execution = executionRepository.getByChat(userId, chatId, executionId)
            ?: throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "execution_not_found",
                message = "Execution not found.",
            )
        return CancelExecutionResult(cancelExecutionInternal(execution))
    }

    private suspend fun executeChatTurnLegacy(
        factory: AgentExecutionRequestFactory,
        finalizer: AgentExecutionFinalizer,
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String?,
        requestOverrides: UserSettingsOverrides,
    ): SendMessageResult {
        val chat = requireOwnedChat(userId, chatId)
        val prepared = factory.prepareChatTurn(userId, chatId, content, clientMessageId, requestOverrides)
        val queuedExecution = try {
            executionRepository.create(prepared.execution)
        } catch (e: ActiveAgentExecutionConflictException) {
            throw BackendV1Exception(HttpStatusCode.Conflict, "chat_already_has_active_execution", "Chat already has an active execution.")
        }
        try {
            val userMessage = messageRepository.append(userId, chatId, ChatRole.USER, content, metadata = prepared.userMessageMetadata)
            chatRepository.update(chat.copy(updatedAt = userMessage.createdAt))
            val runningExecution = executionRepository.update(
                queuedExecution.copy(userMessageId = userMessage.id, status = AgentExecutionStatus.RUNNING, startedAt = Instant.now())
            )
            val eventSink = factory.createEventSink(
                userId = userId,
                chatId = chatId,
                execution = runningExecution,
                messageRepository = messageRepository,
                optionRepository = optionRepository,
                executionRepository = executionRepository,
                eventService = eventService,
                toolCallRepository = toolCallRepository,
                streamingMessagesEnabled = prepared.effectiveSettings.streamingMessages,
                toolEventsEnabled = prepared.effectiveSettings.showToolEvents,
            )
            eventSink.emitExecutionStarted(runningExecution)
            if (prepared.shouldReturnRunning) {
                launcher.startBackgroundExecution(runningExecution, eventSink) {
                    finalizer.runExecution(chat, runningExecution, prepared.conversationKey, prepared.runtimeRequest, eventSink)
                }
                return SendMessageResult(userMessage, null, runningExecution)
            }
            val executionResult = try {
                launcher.runTrackedExecution(runningExecution, eventSink) {
                    finalizer.runExecution(chat, runningExecution, prepared.conversationKey, prepared.runtimeRequest, eventSink)
                }
            } catch (_: ExecutionCancelledException) {
                throw BackendV1Exception(HttpStatusCode.Conflict, "agent_execution_cancelled", "Agent execution was cancelled.")
            }
            return SendMessageResult(userMessage, executionResult.assistantMessage, executionResult.execution)
        } catch (e: BackendV1Exception) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finalizer.markFailed(queuedExecution.id, userId, chatId, "agent_execution_failed", e.message ?: "Agent execution failed.", queuedExecution.usage)
            throw BackendV1Exception(HttpStatusCode.InternalServerError, "agent_execution_failed", "Agent execution failed.")
        }
    }

    private suspend fun resumeOptionLegacy(
        factory: AgentExecutionRequestFactory,
        finalizer: AgentExecutionFinalizer,
        option: Option,
    ): AgentExecution {
        val currentExecution = finalizer.currentExecution(option.executionId, option.userId, option.chatId)
        if (currentExecution.status != AgentExecutionStatus.WAITING_OPTION) {
            throw invalidV1Request("Execution is not waiting for an option.")
        }
        val chat = requireOwnedChat(option.userId, option.chatId)
        val runningExecution = executionRepository.update(
            currentExecution.copy(status = AgentExecutionStatus.RUNNING, finishedAt = null, cancelRequested = false, errorCode = null, errorMessage = null)
        )
        appendOptionAnswered(option, runningExecution.id)
        val prepared = factory.prepareContinuationTurn(runningExecution, option)
        val eventSink = factory.createEventSink(
            userId = option.userId,
            chatId = option.chatId,
            execution = runningExecution,
            messageRepository = messageRepository,
            optionRepository = optionRepository,
            executionRepository = executionRepository,
            eventService = eventService,
            toolCallRepository = toolCallRepository,
            streamingMessagesEnabled = prepared.streamingMessagesEnabled,
            toolEventsEnabled = prepared.toolEventsEnabled,
        )
        launcher.startBackgroundExecution(runningExecution, eventSink) {
            finalizer.runExecution(chat, runningExecution, prepared.conversationKey, prepared.runtimeRequest, eventSink)
        }
        return runningExecution
    }

    private suspend fun cancelExecutionInternal(execution: AgentExecution): AgentExecution {
        if (!execution.status.isActiveForCancellation()) {
            throw invalidV1Request("Execution is not active.")
        }
        val cancellingExecution = executionRepository.update(execution.copy(status = AgentExecutionStatus.CANCELLING, cancelRequested = true))
        if (launcher.cancel(cancellingExecution.id)) {
            return cancellingExecution
        }
        legacyFinalizer?.let { finalizer ->
            return finalizer.markCancelled(cancellingExecution.id, cancellingExecution.userId, cancellingExecution.chatId, cancellingExecution.usage)
        }
        return requireNotNull(runner).markCancelled(cancellingExecution.id, cancellingExecution.userId, cancellingExecution.chatId, cancellingExecution.usage)
    }

    private suspend fun appendOptionAnswered(option: Option, executionId: UUID) {
        eventService.appendDurable(
            userId = option.userId,
            chatId = option.chatId,
            executionId = executionId,
            type = AgentEventType.OPTION_ANSWERED,
            payload = ChoiceAnsweredPayload(
                optionId = option.id,
                status = option.status.value,
                selectedOptionIds = option.answer?.selectedOptionIds?.toList().orEmpty(),
                freeText = option.answer?.freeText,
                metadata = option.answer?.metadata.orEmpty(),
            ),
        )
    }

    private fun createEventSink(userId: String, chatId: UUID, execution: AgentExecution, config: AgentExecutionRuntimeConfig): BackendAgentRuntimeEventSink =
        BackendAgentRuntimeEventSink(
            userId = userId,
            chatId = chatId,
            executionId = execution.id,
            messageRepository = messageRepository,
            optionRepository = optionRepository,
            executionRepository = executionRepository,
            eventService = eventService,
            toolCallRepository = toolCallRepository,
            streamingMessagesEnabled = config.streamingMessages,
            toolEventsEnabled = config.showToolEvents,
            optionsEnabled = requireNotNull(featureFlags).options,
            assistantMessageId = execution.assistantMessageId,
        )

    private suspend fun requireOwnedChat(userId: String, chatId: UUID): Chat =
        chatRepository.get(userId, chatId)
            ?: throw BackendV1Exception(HttpStatusCode.NotFound, "chat_not_found", "Chat not found.")
}

private fun userMessageMetadata(clientMessageId: String?): Map<String, String> =
    clientMessageId?.let { linkedMapOf("clientMessageId" to it) } ?: emptyMap()

private fun Option.toContinuationPayload(): String {
    val answer = answer ?: error("Option answer is required for continuation.")
    val optionById = options.associateBy { it.id }
    val selectedOptions = answer.selectedOptionIds.mapNotNull(optionById::get).map { option ->
        linkedMapOf("id" to option.id, "label" to option.label, "content" to option.content)
    }
    val payload = linkedMapOf<String, Any?>(
        "type" to "option_answer",
        "optionId" to id.toString(),
        "kind" to kind.value,
        "selectionMode" to selectionMode,
        "selectedOptionIds" to answer.selectedOptionIds.toList(),
        "selectedOptions" to selectedOptions,
        "freeText" to answer.freeText,
        "metadata" to answer.metadata,
    )
    return restJsonMapper.writeValueAsString(payload)
}

private fun AgentExecutionStatus.isActiveForCancellation(): Boolean =
    this == AgentExecutionStatus.QUEUED ||
        this == AgentExecutionStatus.RUNNING ||
        this == AgentExecutionStatus.WAITING_OPTION ||
        this == AgentExecutionStatus.CANCELLING
