package ru.souz.backend.execution.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.agent.runtime.BackendConversationTurnException
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.agent.session.AgentStateConflictException
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.http.BackendV1Exception
import ru.souz.llms.LLMResponse

internal class AgentExecutionFinalizer(
    agentStateRepository: AgentStateRepository,
    private val chatRepository: ChatRepository,
    private val executionRepository: AgentExecutionRepository,
    private val turnRunner: BackendConversationTurnRunner,
    private val clientThreadRegistry: ClientThreadRuntimeRegistry? = null,
) {
    private val sessionRepository = AgentStateBackedSessionRepository(agentStateRepository)

    suspend fun runExecution(
        chat: Chat,
        execution: AgentExecution,
        conversationKey: AgentConversationKey,
        turnRequest: BackendConversationTurnRequest,
        eventSink: BackendAgentRuntimeEventSink,
    ): AgentExecution {
        try {
            val executionOutcome = turnRunner.run(
                conversationKey = conversationKey,
                request = turnRequest,
                eventSink = eventSink,
                initialUsage = execution.usage?.toLlmUsage() ?: LLMResponse.Usage(0, 0, 0, 0),
            )
            if (eventSink.hasRequestedOption && executionOutcome is BackendConversationTurnOutcome.Completed) {
                throw BackendV1Exception(
                    status = HttpStatusCode.InternalServerError,
                    code = "internal_error",
                    message = "Execution completed after requesting an option.",
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

                is BackendConversationTurnOutcome.WaitingOption -> persistWaitingOptionExecution(
                    execution = execution,
                    executionOutcome = executionOutcome,
                    conversationKey = conversationKey,
                )
            }
        } catch (e: CancellationException) {
            cancelExecution(execution, eventSink)
        } catch (e: Exception) {
            failExecution(execution, eventSink, e)
        }
    }

    suspend fun finalizeCancelledExecutionIfNeeded(
        executionId: UUID,
        userId: String,
        chatId: UUID,
        eventSink: BackendAgentRuntimeEventSink,
    ) {
        val currentExecution = executionRepository.getByChat(userId, chatId, executionId) ?: return
        if (
            currentExecution.status == AgentExecutionStatus.CANCELLED ||
            currentExecution.status == AgentExecutionStatus.COMPLETED ||
            currentExecution.status == AgentExecutionStatus.FAILED ||
            currentExecution.status == AgentExecutionStatus.WAITING_OPTION
        ) {
            return
        }
        markCancelled(
            executionId = executionId,
            userId = userId,
            chatId = chatId,
            usage = currentExecution.usage,
        )
        eventSink.emitExecutionCancelled()
    }

    suspend fun markFailed(
        executionId: UUID,
        userId: String,
        chatId: UUID,
        errorCode: String,
        errorMessage: String,
        usage: AgentExecutionUsage?,
    ): AgentExecution = withTerminalTransition(executionId) {
        val currentExecution = executionRepository.getByChat(userId, chatId, executionId) ?: return@withTerminalTransition AgentExecution(
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
        executionRepository.update(
            currentExecution.copy(
                status = AgentExecutionStatus.FAILED,
                finishedAt = Instant.now(),
                errorCode = errorCode,
                errorMessage = errorMessage,
                usage = usage ?: currentExecution.usage,
            )
        )
    }

    suspend fun markCancelled(
        executionId: UUID,
        userId: String,
        chatId: UUID,
        usage: AgentExecutionUsage?,
    ): AgentExecution = withTerminalTransition(executionId) {
        persistCancelled(
            executionId = executionId,
            userId = userId,
            chatId = chatId,
            usage = usage,
        )
    }

    internal suspend fun persistCancelled(
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

    suspend fun currentExecution(
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

    private suspend fun persistSuccessfulExecution(
        chat: Chat,
        execution: AgentExecution,
        executionOutcome: BackendConversationTurnOutcome.Completed,
        conversationKey: AgentConversationKey,
        eventSink: BackendAgentRuntimeEventSink,
    ): AgentExecution {
        val persisted = withTerminalTransition(execution.id) {
            val assistantMessage = eventSink.completeAssistantMessage(executionOutcome.output)
            sessionRepository.save(
                conversationKey,
                executionOutcome.session.copy(
                    basedOnMessageSeq = assistantMessage.seq,
                )
            )
            chatRepository.update(chat.copy(updatedAt = assistantMessage.createdAt))

            executionRepository.update(
                currentExecution(execution.id, execution.userId, execution.chatId).copy(
                    assistantMessageId = assistantMessage.id,
                    status = AgentExecutionStatus.COMPLETED,
                    finishedAt = Instant.now(),
                    errorCode = null,
                    errorMessage = null,
                    usage = executionOutcome.usage.toExecutionUsage(),
                )
            )
        }
        eventSink.emitExecutionFinished(persisted)

        return persisted
    }

    private suspend fun persistWaitingOptionExecution(
        execution: AgentExecution,
        executionOutcome: BackendConversationTurnOutcome.WaitingOption,
        conversationKey: AgentConversationKey,
    ): AgentExecution {
        sessionRepository.save(conversationKey, executionOutcome.session)
        val waitingExecution = currentExecution(execution.id, execution.userId, execution.chatId)
        return if (waitingExecution.status == AgentExecutionStatus.WAITING_OPTION) {
            executionRepository.update(
                waitingExecution.copy(
                    usage = executionOutcome.usage.toExecutionUsage(),
                )
            )
        } else {
            executionRepository.update(
                waitingExecution.copy(
                    status = AgentExecutionStatus.WAITING_OPTION,
                    usage = executionOutcome.usage.toExecutionUsage(),
                )
            )
        }
    }

    internal suspend fun <T> withTerminalTransition(executionId: UUID, block: suspend () -> T): T =
        clientThreadRegistry?.withTerminalTransition(executionId, block) ?: block()

    private suspend fun cancelExecution(
        execution: AgentExecution,
        eventSink: BackendAgentRuntimeEventSink,
    ): Nothing {
        withContext(NonCancellable) {
            markCancelled(
                executionId = execution.id,
                userId = execution.userId,
                chatId = execution.chatId,
                usage = execution.usage,
            )
            eventSink.emitExecutionCancelled()
        }
        throw CancellationException("Agent execution was cancelled.")
    }

    private suspend fun failExecution(
        execution: AgentExecution,
        eventSink: BackendAgentRuntimeEventSink,
        error: Exception,
    ): Nothing {
        val failure = error.toExecutionFailure(execution)
        withContext(NonCancellable) {
            markFailed(
                executionId = execution.id,
                userId = execution.userId,
                chatId = execution.chatId,
                errorCode = failure.errorCode,
                errorMessage = failure.errorMessage,
                usage = failure.usage,
            )
            eventSink.emitExecutionFailed(failure.errorCode, failure.errorMessage)
        }
        throw failure.response
    }
}

private data class ExecutionFailure(
    val errorCode: String,
    val errorMessage: String,
    val usage: AgentExecutionUsage?,
    val response: BackendV1Exception,
)

private fun Exception.toExecutionFailure(execution: AgentExecution): ExecutionFailure {
    val turnException = this as? BackendConversationTurnException
    val cause = turnException?.cause ?: this
    val usage = turnException?.usage?.toExecutionUsage() ?: execution.usage
    return when (cause) {
        is BackendV1Exception -> ExecutionFailure(
            errorCode = cause.code,
            errorMessage = cause.message,
            usage = usage,
            response = cause,
        )

        is AgentStateConflictException -> ExecutionFailure(
            errorCode = "state_conflict",
            errorMessage = "Agent state changed before save.",
            usage = usage,
            response = BackendV1Exception(
                status = HttpStatusCode.InternalServerError,
                code = "agent_execution_failed",
                message = "Agent execution failed.",
            ),
        )

        else -> ExecutionFailure(
            errorCode = "agent_execution_failed",
            errorMessage = cause.message ?: "Agent execution failed.",
            usage = usage,
            response = BackendV1Exception(
                status = HttpStatusCode.InternalServerError,
                code = "agent_execution_failed",
                message = "Agent execution failed.",
            ),
        )
    }
}

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
