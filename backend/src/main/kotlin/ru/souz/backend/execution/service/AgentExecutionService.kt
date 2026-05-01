package ru.souz.backend.execution.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.supervisorScope
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationExecution
import ru.souz.backend.agent.runtime.BackendConversationRuntimeFactory
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.chat.service.SendMessageResult
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.repository.AgentEventRepository
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
import ru.souz.llms.LLMResponse

data class CancelExecutionResult(
    val execution: AgentExecution,
)

class AgentExecutionService(
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val agentStateRepository: AgentStateRepository,
    private val effectiveSettingsResolver: EffectiveSettingsResolver,
    private val runtimeFactory: BackendConversationRuntimeFactory,
    private val executionRepository: AgentExecutionRepository,
    private val eventRepository: AgentEventRepository,
    private val eventService: AgentEventService,
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
                    metadata = emptyMap(),
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
                temperature = effectiveSettings.temperature,
                systemPrompt = effectiveSettings.systemPrompt,
                streamingMessages = effectiveSettings.streamingMessages,
            )
            val eventSink = BackendAgentRuntimeEventSink(
                userId = userId,
                chatId = chatId,
                executionId = runningExecution.id,
                messageRepository = messageRepository,
                eventService = eventService,
                streamingMessagesEnabled = effectiveSettings.streamingMessages,
                toolEventsEnabled = effectiveSettings.showToolEvents,
            )
            eventSink.emitExecutionStarted(runningExecution)
            val shouldReturnRunning = effectiveSettings.streamingMessages && featureFlags.wsEvents
            if (shouldReturnRunning) {
                startBackgroundExecution(
                    chat = chat,
                    userMessage = userMessage,
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
                    userMessage = userMessage,
                    execution = runningExecution,
                    conversationKey = conversationKey,
                    turnRequest = turnRequest,
                    eventSink = eventSink,
                )
            }

            activeJobs.register(runningExecution.id, executionJob)
            executionJob.start()
            try {
                executionJob.await()
            } finally {
                activeJobs.unregister(runningExecution.id, executionJob)
            }
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
        userMessage: ru.souz.backend.chat.model.ChatMessage,
        execution: AgentExecution,
        conversationKey: AgentConversationKey,
        turnRequest: BackendConversationTurnRequest,
        eventSink: BackendAgentRuntimeEventSink,
    ) {
        lateinit var executionJob: Deferred<SendMessageResult>
        executionJob = executionScope.async(start = CoroutineStart.LAZY) {
            try {
                runExecution(
                    chat = chat,
                    userMessage = userMessage,
                    execution = execution,
                    conversationKey = conversationKey,
                    turnRequest = turnRequest,
                    eventSink = eventSink,
                )
            } finally {
                activeJobs.unregister(execution.id, executionJob)
            }
        }
        activeJobs.register(execution.id, executionJob)
        executionJob.start()
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
        userMessage: ru.souz.backend.chat.model.ChatMessage,
        execution: AgentExecution,
        conversationKey: AgentConversationKey,
        turnRequest: BackendConversationTurnRequest,
        eventSink: BackendAgentRuntimeEventSink,
    ): SendMessageResult {
        try {
            val runtime = runtimeFactory.create(conversationKey, turnRequest)
            val runtimeExecution = runtime.execute(
                request = turnRequest,
                persistSession = false,
                eventSink = eventSink,
            )
            return persistSuccessfulExecution(
                chat = chat,
                userMessage = userMessage,
                execution = execution,
                runtimeExecution = runtimeExecution,
                conversationKey = conversationKey,
                eventSink = eventSink,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                markCancelled(execution.id, execution.userId, execution.chatId)
                eventSink.emitExecutionCancelled()
            }
            throw ExecutionCancelledException
        } catch (e: BackendV1Exception) {
            withContext(NonCancellable) {
                markFailed(
                    executionId = execution.id,
                    userId = execution.userId,
                    chatId = execution.chatId,
                    errorCode = e.code,
                    errorMessage = e.message,
                )
                eventSink.emitExecutionFailed(e.code, e.message)
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                markFailed(
                    executionId = execution.id,
                    userId = execution.userId,
                    chatId = execution.chatId,
                    errorCode = "agent_execution_failed",
                    errorMessage = e.message ?: "Agent execution failed.",
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
        userMessage: ru.souz.backend.chat.model.ChatMessage,
        execution: AgentExecution,
        runtimeExecution: BackendConversationExecution,
        conversationKey: AgentConversationKey,
        eventSink: BackendAgentRuntimeEventSink,
    ): SendMessageResult {
        val assistantMessage = eventSink.completeAssistantMessage(runtimeExecution.output)
        sessionRepository.save(conversationKey, runtimeExecution.session)
        agentStateRepository.get(execution.userId, execution.chatId)?.let { persistedState ->
            agentStateRepository.save(
                persistedState.copy(
                    basedOnMessageSeq = assistantMessage.seq,
                    updatedAt = Instant.now(),
                    rowVersion = persistedState.rowVersion + 1,
                )
            )
        }
        chatRepository.update(chat.copy(updatedAt = assistantMessage.createdAt))

        val finalExecution = executionRepository.update(
            currentExecution(execution.id, execution.userId, execution.chatId).copy(
                assistantMessageId = assistantMessage.id,
                status = AgentExecutionStatus.COMPLETED,
                finishedAt = Instant.now(),
                errorCode = null,
                errorMessage = null,
                usage = runtimeExecution.usage.toExecutionUsage(),
            )
        )
        eventSink.emitExecutionFinished(finalExecution)

        return SendMessageResult(
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            execution = finalExecution,
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
    ): AgentExecution {
        val currentExecution = currentExecution(executionId, userId, chatId)
        return executionRepository.update(
            currentExecution.copy(
                status = AgentExecutionStatus.CANCELLED,
                cancelRequested = true,
                finishedAt = Instant.now(),
                errorCode = "agent_execution_cancelled",
                errorMessage = "Agent execution was cancelled.",
            )
        )
    }

    private suspend fun markFailed(
        executionId: UUID,
        userId: String,
        chatId: UUID,
        errorCode: String,
        errorMessage: String,
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
            usage = null,
            metadata = emptyMap(),
        )
        return executionRepository.update(
            currentExecution.copy(
                status = AgentExecutionStatus.FAILED,
                finishedAt = Instant.now(),
                errorCode = errorCode,
                errorMessage = errorMessage,
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

    private fun LLMResponse.Usage.toExecutionUsage(): AgentExecutionUsage =
        AgentExecutionUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            precachedTokens = precachedTokens,
        )
}

private fun AgentExecutionStatus.isActiveForCancellation(): Boolean =
    this == AgentExecutionStatus.QUEUED ||
        this == AgentExecutionStatus.RUNNING ||
        this == AgentExecutionStatus.WAITING_CHOICE ||
        this == AgentExecutionStatus.CANCELLING

private object ExecutionCancelledException : RuntimeException()

private class ActiveExecutionJobRegistry {
    private val mutex = Mutex()
    private val jobs = LinkedHashMap<UUID, Deferred<*>>()

    suspend fun register(executionId: UUID, job: Deferred<*>) = mutex.withLock {
        jobs[executionId] = job
    }

    suspend fun unregister(executionId: UUID, job: Deferred<*>) = mutex.withLock {
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
