package ru.souz.backend.execution.service

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.souz.backend.agent.model.BackendAgentRuntimeException
import ru.souz.backend.agent.model.BackendAgentRuntimeOutcome
import ru.souz.backend.agent.model.BackendAgentTurn
import ru.souz.backend.agent.runtime.BackendAgentRuntime
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.agent.session.AgentStateConflictException
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.http.BackendV1Exception
import ru.souz.llms.LLMResponse

internal class AgentExecutionRunner(
    agentStateRepository: AgentStateRepository,
    private val chatRepository: ChatRepository,
    private val executionRepository: AgentExecutionRepository,
    private val runtime: BackendAgentRuntime,
) {
    private val sessionRepository = AgentStateBackedSessionRepository(agentStateRepository)

    suspend fun run(
        chat: Chat,
        execution: AgentExecution,
        turn: BackendAgentTurn,
        eventSink: BackendAgentRuntimeEventSink,
    ): PersistedExecutionResult {
        try {
            val executionOutcome = runtime.run(
                turn = turn,
                eventSink = eventSink,
                initialUsage = execution.usage?.toLlmUsage() ?: LLMResponse.Usage(0, 0, 0, 0),
            )
            if (eventSink.hasRequestedOption && executionOutcome is BackendAgentRuntimeOutcome.Completed) {
                throw BackendV1Exception(
                    status = HttpStatusCode.InternalServerError,
                    code = "internal_error",
                    message = "Execution completed after requesting an option.",
                )
            }
            return when (executionOutcome) {
                is BackendAgentRuntimeOutcome.Completed -> persistSuccessfulExecution(
                    chat = chat,
                    execution = execution,
                    executionOutcome = executionOutcome,
                    turn = turn,
                    eventSink = eventSink,
                )
                is BackendAgentRuntimeOutcome.WaitingOption -> persistWaitingOptionExecution(
                    execution = execution,
                    executionOutcome = executionOutcome,
                    turn = turn,
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
        } catch (e: BackendAgentRuntimeException) {
            val cause = e.cause ?: e
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

    suspend fun markCancelled(
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
        executionOutcome: BackendAgentRuntimeOutcome.Completed,
        turn: BackendAgentTurn,
        eventSink: BackendAgentRuntimeEventSink,
    ): PersistedExecutionResult {
        val assistantMessage = eventSink.completeAssistantMessage(executionOutcome.output)
        sessionRepository.save(
            turn.conversationKey,
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

    private suspend fun persistWaitingOptionExecution(
        execution: AgentExecution,
        executionOutcome: BackendAgentRuntimeOutcome.WaitingOption,
        turn: BackendAgentTurn,
    ): PersistedExecutionResult {
        sessionRepository.save(turn.conversationKey, executionOutcome.session)
        val waitingExecution = currentExecution(execution.id, execution.userId, execution.chatId)
        return PersistedExecutionResult(
            assistantMessage = null,
            execution = if (waitingExecution.status == AgentExecutionStatus.WAITING_OPTION) {
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
            },
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
