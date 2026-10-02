package ru.souz.backend.execution.service

import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.agent.runtime.BackendConversationTurnException
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.session.AgentStateConflictException
import ru.souz.backend.agent.session.toState
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.http.BackendV1Exception
import ru.souz.llms.LLMResponse

internal class AgentExecutionFinalizer(
    private val executionRepository: AgentExecutionRepository,
    private val turnRunner: BackendConversationTurnRunner,
    private val clientThreadRegistry: ClientThreadRuntimeRegistry,
) {
    private val logger = LoggerFactory.getLogger(AgentExecutionFinalizer::class.java)

    suspend fun runExecution(
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
            currentCoroutineContext().ensureActive()
            return if (executionOutcome is BackendConversationTurnOutcome.Completed) {
                withContext(NonCancellable) {
                    val persisted = clientThreadRegistry.withTerminalTransition(execution.id) {
                        persistTurn(execution, executionOutcome, conversationKey, eventSink)
                    }
                    eventSink.emitTerminal(persisted)
                    persisted
                }
            } else persistTurn(execution, executionOutcome, conversationKey, eventSink)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failExecution(execution, eventSink, e)
        }
    }

    suspend fun finalizeCancelledExecutionIfNeeded(
        execution: AgentExecution,
        eventSink: BackendAgentRuntimeEventSink,
    ): AgentExecution? = withContext(NonCancellable) {
        val current = executionRepository.getByChat(execution.userId, execution.chatId, execution.id)
            ?: return@withContext null
        val persisted = transition(execution, AgentExecutionStatus.CANCELLED, usage = current.usage)
        eventSink.emitTerminal(persisted)
        persisted
    }

    suspend fun markFailed(
        execution: AgentExecution,
        errorCode: String,
        errorMessage: String,
        usage: AgentExecutionUsage? = execution.usage,
    ): AgentExecution = transition(execution, AgentExecutionStatus.FAILED, errorCode, errorMessage, usage)

    internal suspend fun transition(
        execution: AgentExecution,
        status: AgentExecutionStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
        usage: AgentExecutionUsage? = execution.usage,
    ): AgentExecution = clientThreadRegistry.withTerminalTransition(execution.id) {
        var current = currentExecution(execution.id, execution.userId, execution.chatId)
        while (current.status.isActive()) {
            val cancelling = status == AgentExecutionStatus.CANCELLING
            if (!cancelling && current.runtimeOwner != execution.runtimeOwner) break
            // WAITING_OPTION is published only after the event and continuation are durable.
            if (!cancelling && current.status == AgentExecutionStatus.WAITING_OPTION && !current.cancelRequested) {
                return@withTerminalTransition current
            }
            val cancelled = !cancelling && (status == AgentExecutionStatus.CANCELLED || current.cancelRequested ||
                current.status == AgentExecutionStatus.CANCELLING)
            executionRepository.transitionIfCurrent(
                expected = current,
                status = if (cancelled) AgentExecutionStatus.CANCELLED else status,
                errorCode = if (cancelled) "agent_execution_cancelled" else errorCode,
                errorMessage = if (cancelled) "Agent execution was cancelled." else errorMessage,
                usage = usage,
            )?.let { return@withTerminalTransition it }
            current = currentExecution(execution.id, execution.userId, execution.chatId)
        }
        current
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

    private suspend fun persistTurn(
        execution: AgentExecution,
        outcome: BackendConversationTurnOutcome,
        conversationKey: AgentConversationKey,
        eventSink: BackendAgentRuntimeEventSink,
    ): AgentExecution {
        val committed = executionRepository.commitTurn(
            expected = execution,
            state = outcome.session.toState(conversationKey),
            usage = outcome.usage.toExecutionUsage(),
            output = (outcome as? BackendConversationTurnOutcome.Completed)?.output,
            assistantMessageId = eventSink.finalAssistantMessageId,
        ) ?: run {
            val current = currentExecution(execution.id, execution.userId, execution.chatId)
            if (current.cancelRequested || current.status == AgentExecutionStatus.CANCELLING) {
                throw CancellationException("Execution cancellation was committed before the turn.")
            }
            return current
        }
        if (outcome is BackendConversationTurnOutcome.Completed) {
            logger.atInfo()
                .addKeyValue("event", "execution.token_usage")
                .addKeyValue("input_tokens", outcome.usage.promptTokens)
                .addKeyValue("output_tokens", outcome.usage.completionTokens)
                .addKeyValue("total_tokens", outcome.usage.totalTokens)
                .addKeyValue("cached_input_tokens", outcome.usage.precachedTokens)
                .log("Backend execution token usage")
            eventSink.emitAssistantMessageCompleted(checkNotNull(committed.assistantMessage), committed.assistantMessageCreated)
        }
        return committed.execution
    }

    private suspend fun failExecution(
        execution: AgentExecution,
        eventSink: BackendAgentRuntimeEventSink,
        error: Exception,
    ): Nothing {
        val turnException = error as? BackendConversationTurnException
        val cause = turnException?.cause ?: error
        val response = cause as? BackendV1Exception ?: BackendV1Exception(
            status = HttpStatusCode.InternalServerError,
            code = "agent_execution_failed",
            message = "Agent execution failed.",
        )
        val stateConflict = cause is AgentStateConflictException
        val cancelled = withContext(NonCancellable) {
            val persisted = markFailed(
                execution = execution,
                errorCode = if (stateConflict) "state_conflict" else response.code,
                errorMessage = if (stateConflict) "Agent state changed before save." else cause.message ?: response.message,
                usage = turnException?.usage?.toExecutionUsage() ?: execution.usage,
            )
            eventSink.emitTerminal(persisted)
            persisted.status == AgentExecutionStatus.CANCELLED
        }
        if (cancelled) throw CancellationException("Execution cancellation won the terminal transition.")
        throw response
    }
}

private fun LLMResponse.Usage.toExecutionUsage(): AgentExecutionUsage =
    AgentExecutionUsage(promptTokens, completionTokens, totalTokens, precachedTokens)

private fun AgentExecutionUsage.toLlmUsage(): LLMResponse.Usage =
    LLMResponse.Usage(promptTokens, completionTokens, totalTokens, precachedTokens)
