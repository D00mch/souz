package ru.souz.backend.execution.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.supervisorScope
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnException
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.agent.session.AgentStateConflictException
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.choices.model.Choice
import ru.souz.backend.choices.repository.ChoiceRepository
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.execution.repository.ActiveAgentExecutionConflictException
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.http.BackendV1Exception
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

data class CancelExecutionResult(
    val execution: AgentExecution,
)

class AgentExecutionService internal constructor(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val agentStateRepository: AgentStateRepository,
    private val effectiveSettingsResolver: EffectiveSettingsResolver,
    private val turnRunner: BackendConversationTurnRunner,
    private val executionRepository: AgentExecutionRepository,
    private val choiceRepository: ChoiceRepository,
    private val eventRepository: AgentEventRepository,
    private val eventService: AgentEventService,
    private val toolCallRepository: ToolCallRepository,
    private val featureFlags: BackendFeatureFlags,
    private val executionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val sessionRepository = AgentStateBackedSessionRepository(agentStateRepository)
    private val activeJobs = ActiveExecutionJobRegistry()

    suspend fun executeChatTurn(
        userId: String,
        chatId: UUID,
        content: String,
        clientMessageId: String? = null,
        requestOverrides: UserSettingsOverrides = UserSettingsOverrides(),
    ): SendMessageResult = supervisorScope {
        val chat = requireOwnedChat(userId, chatId)
        val effectiveSettings = effectiveSettingsResolver.resolve(userId, requestOverrides)
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
                    metadata = executionMetadata(
                        contextSize = effectiveSettings.contextSize,
                        temperature = effectiveSettings.temperature,
                        locale = effectiveSettings.locale.toLanguageTag(),
                        timeZone = effectiveSettings.timeZone.id,
                        systemPrompt = effectiveSettings.systemPrompt,
                        streamingMessages = effectiveSettings.streamingMessages,
                        showToolEvents = effectiveSettings.showToolEvents,
                    ),
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
            val conversationKey = AgentConversationKey(userId = userId, conversationId = chatId.toString())
            val turnRequest = BackendConversationTurnRequest(
                prompt = content,
                model = effectiveSettings.defaultModel.alias,
                contextSize = effectiveSettings.contextSize,
                locale = effectiveSettings.locale.toLanguageTag(),
                timeZone = effectiveSettings.timeZone.id,
                executionId = runningExecution.id.toString(),
                temperature = effectiveSettings.temperature,
                systemPrompt = effectiveSettings.systemPrompt,
                streamingMessages = effectiveSettings.streamingMessages,
            )
            val eventSink = BackendAgentRuntimeEventSink(
                userId = userId,
                chatId = chatId,
                executionId = runningExecution.id,
                messageRepository = messageRepository,
                choiceRepository = choiceRepository,
                executionRepository = executionRepository,
                eventService = eventService,
                toolCallRepository = toolCallRepository,
                streamingMessagesEnabled = effectiveSettings.streamingMessages,
                toolEventsEnabled = effectiveSettings.showToolEvents,
                choicesEnabled = featureFlags.choices,
                assistantMessageId = runningExecution.assistantMessageId,
            )
            eventSink.emitExecutionStarted(runningExecution)
            val shouldReturnRunning = effectiveSettings.streamingMessages && featureFlags.wsEvents
            if (shouldReturnRunning) {
                startBackgroundExecution(
                    chat = chat,
                    execution = runningExecution,
                    conversationKey = conversationKey,
                    turnRequest = turnRequest,
                    eventSink = eventSink,
                )
                return@supervisorScope SendMessageResult(
                    userMessage = userMessage,
                    assistantMessage = null,
                    execution = runningExecution,
                )
            }
            val executionJob = async(start = CoroutineStart.LAZY) {
                runExecution(
                    chat = chat,
                    execution = runningExecution,
                    conversationKey = conversationKey,
                    turnRequest = turnRequest,
                    eventSink = eventSink,
                )
            }

            activeJobs.register(runningExecution.id, executionJob)
            executionJob.start()
            val executionResult = try {
                executionJob.await()
            } finally {
                activeJobs.unregister(runningExecution.id, executionJob)
            }
            return@supervisorScope SendMessageResult(
                userMessage = userMessage,
                assistantMessage = executionResult.assistantMessage,
                execution = executionResult.execution,
            )
        } catch (e: BackendV1Exception) {
            throw e
        } catch (e: ExecutionCancelledException) {
            throw BackendV1Exception(
                status = HttpStatusCode.Conflict,
                code = "agent_execution_cancelled",
                message = "Agent execution was cancelled.",
            )
        } catch (e: Exception) {
            markFailed(
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

    private suspend fun startBackgroundExecution(
        chat: Chat,
        execution: AgentExecution,
        conversationKey: AgentConversationKey,
        turnRequest: BackendConversationTurnRequest,
        eventSink: BackendAgentRuntimeEventSink,
    ) {
        val executionJob = executionScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                runExecution(
                    chat = chat,
                    execution = execution,
                    conversationKey = conversationKey,
                    turnRequest = turnRequest,
                    eventSink = eventSink,
                )
            } finally {
                activeJobs.unregister(execution.id, currentCoroutineContext()[Job] ?: return@async)
            }
        }
        if (!executionJob.isCompleted) {
            activeJobs.register(execution.id, executionJob)
        }
    }

    suspend fun resumeChoice(choice: Choice): AgentExecution {
        val currentExecution = currentExecution(choice.executionId, choice.userId, choice.chatId)
        if (currentExecution.status != AgentExecutionStatus.WAITING_CHOICE) {
            throw invalidV1Request("Execution is not waiting for a choice.")
        }
        val chat = requireOwnedChat(choice.userId, choice.chatId)
        val runningExecution = executionRepository.update(
            currentExecution.copy(
                status = AgentExecutionStatus.RUNNING,
                finishedAt = null,
                cancelRequested = false,
                errorCode = null,
                errorMessage = null,
            )
        )
        eventService.append(
            userId = choice.userId,
            chatId = choice.chatId,
            executionId = runningExecution.id,
            type = AgentEventType.CHOICE_ANSWERED,
            payload = mapOf(
                "choiceId" to choice.id.toString(),
                "status" to choice.status.value,
                "selectedOptionIds" to restJsonMapper.writeValueAsString(choice.answer?.selectedOptionIds?.toList().orEmpty()),
                "freeText" to (choice.answer?.freeText ?: ""),
                "metadata" to restJsonMapper.writeValueAsString(choice.answer?.metadata.orEmpty()),
            ),
        )

        val turnRequest = continuationTurnRequest(runningExecution, choice)
        val eventSink = BackendAgentRuntimeEventSink(
            userId = choice.userId,
            chatId = choice.chatId,
            executionId = runningExecution.id,
            messageRepository = messageRepository,
            choiceRepository = choiceRepository,
            executionRepository = executionRepository,
            eventService = eventService,
            toolCallRepository = toolCallRepository,
            streamingMessagesEnabled = turnRequest.streamingMessages == true,
            toolEventsEnabled = executionMetadataBoolean(runningExecution, METADATA_SHOW_TOOL_EVENTS) ?: false,
            choicesEnabled = featureFlags.choices,
            assistantMessageId = runningExecution.assistantMessageId,
        )
        startBackgroundExecution(
            chat = chat,
            execution = runningExecution,
            conversationKey = AgentConversationKey(
                userId = choice.userId,
                conversationId = choice.chatId.toString(),
            ),
            turnRequest = turnRequest,
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

    private suspend fun runExecution(
        chat: Chat,
        execution: AgentExecution,
        conversationKey: AgentConversationKey,
        turnRequest: BackendConversationTurnRequest,
        eventSink: BackendAgentRuntimeEventSink,
    ): PersistedExecutionResult {
        try {
            val executionOutcome = turnRunner.run(
                conversationKey = conversationKey,
                request = turnRequest,
                eventSink = eventSink,
                initialUsage = execution.usage?.toLlmUsage() ?: LLMResponse.Usage(0, 0, 0, 0),
            )
            if (eventSink.hasRequestedChoice && executionOutcome is BackendConversationTurnOutcome.Completed) {
                throw BackendV1Exception(
                    status = HttpStatusCode.InternalServerError,
                    code = "internal_error",
                    message = "Execution completed after requesting a choice.",
                )
            }
            return when (executionOutcome) {
                is BackendConversationTurnOutcome.Completed -> persistSuccessfulExecution(
                    chat = chat,
                    execution = execution,
                    executionOutcome = executionOutcome,
                    conversationKey = conversationKey,
                    eventSink = eventSink,
                )

                is BackendConversationTurnOutcome.WaitingChoice -> persistWaitingChoiceExecution(
                    execution = execution,
                    executionOutcome = executionOutcome,
                    conversationKey = conversationKey,
                )
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                markCancelled(
                    executionId = execution.id,
                    userId = execution.userId,
                    chatId = execution.chatId,
                    usage = execution.usage,
                )
                eventSink.emitExecutionCancelled()
            }
            throw ExecutionCancelledException
        } catch (e: BackendConversationTurnException) {
            val cause = e.cause ?: e
            if (cause is CancellationException) {
                withContext(NonCancellable) {
                    markCancelled(
                        executionId = execution.id,
                        userId = execution.userId,
                        chatId = execution.chatId,
                        usage = e.usage.toExecutionUsage(),
                    )
                    eventSink.emitExecutionCancelled()
                }
                throw ExecutionCancelledException
            }
            if (cause is BackendV1Exception) {
                withContext(NonCancellable) {
                    markFailed(
                        executionId = execution.id,
                        userId = execution.userId,
                        chatId = execution.chatId,
                        errorCode = cause.code,
                        errorMessage = cause.message,
                        usage = e.usage.toExecutionUsage(),
                    )
                    eventSink.emitExecutionFailed(cause.code, cause.message)
                }
                throw cause
            }
            if (cause is AgentStateConflictException) {
                withContext(NonCancellable) {
                    markFailed(
                        executionId = execution.id,
                        userId = execution.userId,
                        chatId = execution.chatId,
                        errorCode = "state_conflict",
                        errorMessage = "Agent state changed before save.",
                        usage = e.usage.toExecutionUsage(),
                    )
                    eventSink.emitExecutionFailed(
                        errorCode = "state_conflict",
                        errorMessage = "Agent state changed before save.",
                    )
                }
                throw BackendV1Exception(
                    status = HttpStatusCode.InternalServerError,
                    code = "agent_execution_failed",
                    message = "Agent execution failed.",
                )
            }
            withContext(NonCancellable) {
                markFailed(
                    executionId = execution.id,
                    userId = execution.userId,
                    chatId = execution.chatId,
                    errorCode = "agent_execution_failed",
                    errorMessage = cause.message ?: "Agent execution failed.",
                    usage = e.usage.toExecutionUsage(),
                )
                eventSink.emitExecutionFailed(
                    errorCode = "agent_execution_failed",
                    errorMessage = cause.message ?: "Agent execution failed.",
                )
            }
            throw BackendV1Exception(
                status = HttpStatusCode.InternalServerError,
                code = "agent_execution_failed",
                message = "Agent execution failed.",
            )
        } catch (e: BackendV1Exception) {
            withContext(NonCancellable) {
                markFailed(
                    executionId = execution.id,
                    userId = execution.userId,
                    chatId = execution.chatId,
                    errorCode = e.code,
                    errorMessage = e.message,
                    usage = execution.usage,
                )
                eventSink.emitExecutionFailed(e.code, e.message)
            }
            throw e
        } catch (e: AgentStateConflictException) {
            withContext(NonCancellable) {
                markFailed(
                    executionId = execution.id,
                    userId = execution.userId,
                    chatId = execution.chatId,
                    errorCode = "state_conflict",
                    errorMessage = "Agent state changed before save.",
                    usage = execution.usage,
                )
                eventSink.emitExecutionFailed(
                    errorCode = "state_conflict",
                    errorMessage = "Agent state changed before save.",
                )
            }
            throw BackendV1Exception(
                status = HttpStatusCode.InternalServerError,
                code = "agent_execution_failed",
                message = "Agent execution failed.",
            )
        } catch (e: Exception) {
            withContext(NonCancellable) {
                markFailed(
                    executionId = execution.id,
                    userId = execution.userId,
                    chatId = execution.chatId,
                    errorCode = "agent_execution_failed",
                    errorMessage = e.message ?: "Agent execution failed.",
                    usage = execution.usage,
                )
                eventSink.emitExecutionFailed(
                    errorCode = "agent_execution_failed",
                    errorMessage = e.message ?: "Agent execution failed.",
                )
            }
            throw BackendV1Exception(
                status = HttpStatusCode.InternalServerError,
                code = "agent_execution_failed",
                message = "Agent execution failed.",
            )
        }
    }

    private suspend fun persistSuccessfulExecution(
        chat: Chat,
        execution: AgentExecution,
        executionOutcome: BackendConversationTurnOutcome.Completed,
        conversationKey: AgentConversationKey,
        eventSink: BackendAgentRuntimeEventSink,
    ): PersistedExecutionResult {
        val assistantMessage = eventSink.completeAssistantMessage(executionOutcome.output)
        sessionRepository.save(
            conversationKey,
            executionOutcome.session.copy(
                basedOnMessageSeq = assistantMessage.seq,
            )
        )
        chatRepository.update(chat.copy(updatedAt = assistantMessage.createdAt))

        val finalExecution = executionRepository.update(
            currentExecution(execution.id, execution.userId, execution.chatId).copy(
                assistantMessageId = assistantMessage.id,
                status = AgentExecutionStatus.COMPLETED,
                finishedAt = Instant.now(),
                errorCode = null,
                errorMessage = null,
                usage = executionOutcome.usage.toExecutionUsage(),
            )
        )
        eventSink.emitExecutionFinished(finalExecution)

        return PersistedExecutionResult(
            assistantMessage = assistantMessage,
            execution = finalExecution,
        )
    }

    private suspend fun persistWaitingChoiceExecution(
        execution: AgentExecution,
        executionOutcome: BackendConversationTurnOutcome.WaitingChoice,
        conversationKey: AgentConversationKey,
    ): PersistedExecutionResult {
        sessionRepository.save(conversationKey, executionOutcome.session)
        val waitingExecution = currentExecution(execution.id, execution.userId, execution.chatId)
        return PersistedExecutionResult(
            assistantMessage = null,
            execution = if (waitingExecution.status == AgentExecutionStatus.WAITING_CHOICE) {
                executionRepository.update(
                    waitingExecution.copy(
                        usage = executionOutcome.usage.toExecutionUsage(),
                    )
                )
            } else {
                executionRepository.update(
                    waitingExecution.copy(
                        status = AgentExecutionStatus.WAITING_CHOICE,
                        usage = executionOutcome.usage.toExecutionUsage(),
                    )
                )
            },
        )
    }

    private suspend fun cancelExecutionInternal(execution: AgentExecution): AgentExecution {
        if (!execution.status.isActiveForCancellation()) {
            throw invalidV1Request("Execution is not active.")
        }
        val cancellingExecution = executionRepository.update(
            execution.copy(
                status = AgentExecutionStatus.CANCELLING,
                cancelRequested = true,
            )
        )
        if (!activeJobs.cancel(cancellingExecution.id)) {
            return executionRepository.update(
                cancellingExecution.copy(
                    status = AgentExecutionStatus.CANCELLED,
                    finishedAt = Instant.now(),
                    errorCode = "agent_execution_cancelled",
                    errorMessage = "Agent execution was cancelled.",
                )
            )
        }
        return cancellingExecution
    }

    private suspend fun markCancelled(
        executionId: UUID,
        userId: String,
        chatId: UUID,
        usage: AgentExecutionUsage?,
    ): AgentExecution {
        val currentExecution = currentExecution(executionId, userId, chatId)
        return executionRepository.update(
            currentExecution.copy(
                status = AgentExecutionStatus.CANCELLED,
                cancelRequested = true,
                finishedAt = Instant.now(),
                errorCode = "agent_execution_cancelled",
                errorMessage = "Agent execution was cancelled.",
                usage = usage ?: currentExecution.usage,
            )
        )
    }

    private suspend fun markFailed(
        executionId: UUID,
        userId: String,
        chatId: UUID,
        errorCode: String,
        errorMessage: String,
        usage: AgentExecutionUsage?,
    ): AgentExecution {
        val currentExecution = executionRepository.getByChat(userId, chatId, executionId) ?: return AgentExecution(
            id = executionId,
            userId = userId,
            chatId = chatId,
            userMessageId = null,
            assistantMessageId = null,
            status = AgentExecutionStatus.FAILED,
            requestId = null,
            clientMessageId = null,
            model = null,
            provider = null,
            startedAt = Instant.now(),
            finishedAt = Instant.now(),
            cancelRequested = false,
            errorCode = errorCode,
            errorMessage = errorMessage,
            usage = usage,
            metadata = emptyMap(),
        )
        return executionRepository.update(
            currentExecution.copy(
                status = AgentExecutionStatus.FAILED,
                finishedAt = Instant.now(),
                errorCode = errorCode,
                errorMessage = errorMessage,
                usage = usage ?: currentExecution.usage,
            )
        )
    }

    private suspend fun currentExecution(
        executionId: UUID,
        userId: String,
        chatId: UUID,
    ): AgentExecution =
        executionRepository.getByChat(userId, chatId, executionId)
            ?: throw BackendV1Exception(
                status = HttpStatusCode.InternalServerError,
                code = "internal_error",
                message = "Execution not found.",
            )

    private suspend fun requireOwnedChat(userId: String, chatId: UUID): Chat =
        chatRepository.get(userId, chatId)
            ?: throw BackendV1Exception(
                status = HttpStatusCode.NotFound,
                code = "chat_not_found",
                message = "Chat not found.",
            )

    private fun userMessageMetadata(clientMessageId: String?): Map<String, String> =
        clientMessageId?.let { linkedMapOf("clientMessageId" to it) } ?: emptyMap()

    private fun continuationTurnRequest(
        execution: AgentExecution,
        choice: Choice,
    ): BackendConversationTurnRequest =
        BackendConversationTurnRequest(
            prompt = choice.toContinuationInput(),
            model = execution.model?.alias
                ?: throw BackendV1Exception(
                    status = HttpStatusCode.InternalServerError,
                    code = "internal_error",
                    message = "Execution model is missing.",
                ),
            contextSize = executionMetadataInt(execution, METADATA_CONTEXT_SIZE)
                ?: throw BackendV1Exception(
                    status = HttpStatusCode.InternalServerError,
                    code = "internal_error",
                    message = "Execution contextSize is missing.",
                ),
            locale = execution.metadata[METADATA_LOCALE]
                ?: throw BackendV1Exception(
                    status = HttpStatusCode.InternalServerError,
                    code = "internal_error",
                    message = "Execution locale is missing.",
                ),
            timeZone = execution.metadata[METADATA_TIME_ZONE]
                ?: throw BackendV1Exception(
                    status = HttpStatusCode.InternalServerError,
                    code = "internal_error",
                    message = "Execution timeZone is missing.",
                ),
            executionId = execution.id.toString(),
            temperature = executionMetadataFloat(execution, METADATA_TEMPERATURE),
            systemPrompt = execution.metadata[METADATA_SYSTEM_PROMPT]?.takeIf { it.isNotEmpty() },
            streamingMessages = executionMetadataBoolean(execution, METADATA_STREAMING_MESSAGES),
        )

    private fun executionMetadata(
        contextSize: Int,
        temperature: Float,
        locale: String,
        timeZone: String,
        systemPrompt: String?,
        streamingMessages: Boolean,
        showToolEvents: Boolean,
    ): Map<String, String> = buildMap {
        put(METADATA_CONTEXT_SIZE, contextSize.toString())
        put(METADATA_TEMPERATURE, temperature.toString())
        put(METADATA_LOCALE, locale)
        put(METADATA_TIME_ZONE, timeZone)
        put(METADATA_STREAMING_MESSAGES, streamingMessages.toString())
        put(METADATA_SHOW_TOOL_EVENTS, showToolEvents.toString())
        systemPrompt?.let { put(METADATA_SYSTEM_PROMPT, it) }
    }

    private fun executionMetadataInt(
        execution: AgentExecution,
        key: String,
    ): Int? = execution.metadata[key]?.toIntOrNull()

    private fun executionMetadataFloat(
        execution: AgentExecution,
        key: String,
    ): Float? = execution.metadata[key]?.toFloatOrNull()

    private fun executionMetadataBoolean(
        execution: AgentExecution,
        key: String,
    ): Boolean? = execution.metadata[key]?.toBooleanStrictOrNull()

    private fun LLMResponse.Usage.toExecutionUsage(): AgentExecutionUsage =
        AgentExecutionUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            precachedTokens = precachedTokens,
        )

    private fun AgentExecutionUsage.toLlmUsage(): LLMResponse.Usage =
        LLMResponse.Usage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            precachedTokens = precachedTokens,
        )
}

private data class PersistedExecutionResult(
    val assistantMessage: ChatMessage?,
    val execution: AgentExecution,
)

private fun Choice.toContinuationInput(): String {
    val answer = answer ?: error("Choice answer is required for continuation.")
    val optionById = options.associateBy { it.id }
    val selectedOptions = answer.selectedOptionIds.mapNotNull(optionById::get).map { option ->
        linkedMapOf(
            "id" to option.id,
            "label" to option.label,
            "content" to option.content,
        )
    }
    val payload = linkedMapOf<String, Any?>(
        "type" to "choice_answer",
        "choiceId" to id.toString(),
        "kind" to kind.value,
        "selectionMode" to selectionMode,
        "selectedOptionIds" to answer.selectedOptionIds.toList(),
        "selectedOptions" to selectedOptions,
        "freeText" to answer.freeText,
        "metadata" to answer.metadata,
    )
    return "$CHOICE_CONTINUATION_PREFIX ${restJsonMapper.writeValueAsString(payload)}"
}

private fun AgentExecutionStatus.isActiveForCancellation(): Boolean =
    this == AgentExecutionStatus.QUEUED ||
        this == AgentExecutionStatus.RUNNING ||
        this == AgentExecutionStatus.WAITING_CHOICE ||
        this == AgentExecutionStatus.CANCELLING

private object ExecutionCancelledException : RuntimeException()

private const val METADATA_CONTEXT_SIZE = "contextSize"
private const val METADATA_TEMPERATURE = "temperature"
private const val METADATA_LOCALE = "locale"
private const val METADATA_TIME_ZONE = "timeZone"
private const val METADATA_SYSTEM_PROMPT = "systemPrompt"
private const val METADATA_STREAMING_MESSAGES = "streamingMessages"
private const val METADATA_SHOW_TOOL_EVENTS = "showToolEvents"
private const val CHOICE_CONTINUATION_PREFIX = "__choice_answer__"

private class ActiveExecutionJobRegistry {
    private val mutex = Mutex()
    private val jobs = LinkedHashMap<UUID, Job>()

    suspend fun register(executionId: UUID, job: Job) = mutex.withLock {
        jobs[executionId] = job
    }

    suspend fun unregister(executionId: UUID, job: Job) = mutex.withLock {
        if (jobs[executionId] == job) {
            jobs.remove(executionId)
        }
    }

    suspend fun cancel(executionId: UUID): Boolean = mutex.withLock {
        val job = jobs[executionId] ?: return@withLock false
        job.cancel(CancellationException("Execution cancelled by user request."))
        true
    }
}
