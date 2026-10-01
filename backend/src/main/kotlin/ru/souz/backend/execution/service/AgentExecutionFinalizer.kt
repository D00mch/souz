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
    private val clientThreadRegistry: ClientThreadRuntimeRegistry? = null,
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
            return when (executionOutcome) {
                is BackendConversationTurnOutcome.Completed -> persistSuccessfulExecution(
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
        val persisted = persistTerminal(execution, AgentExecutionStatus.CANCELLED, usage = current.usage)
        eventSink.emitTerminal(persisted)
        persisted
    }

    suspend fun markFailed(
        execution: AgentExecution,
        errorCode: String,
        errorMessage: String,
        usage: AgentExecutionUsage? = execution.usage,
    ): AgentExecution = persistTerminal(execution, AgentExecutionStatus.FAILED, errorCode, errorMessage, usage)

    private suspend fun persistTerminal(
        execution: AgentExecution,
        status: AgentExecutionStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
        usage: AgentExecutionUsage?,
    ): AgentExecution = withTerminalTransition(execution.id) {
        var current = currentExecution(execution.id, execution.userId, execution.chatId)
        while (current.status.isActive() && current.runtimeOwner == execution.runtimeOwner) {
            // WAITING_OPTION is published only after the event and continuation are durable.
            if (current.status == AgentExecutionStatus.WAITING_OPTION && !current.cancelRequested) {
                return@withTerminalTransition current
            }
            val cancelled = status == AgentExecutionStatus.CANCELLED || current.cancelRequested ||
                current.status == AgentExecutionStatus.CANCELLING
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

    private suspend fun persistSuccessfulExecution(
        execution: AgentExecution,
        executionOutcome: BackendConversationTurnOutcome.Completed,
        conversationKey: AgentConversationKey,
        eventSink: BackendAgentRuntimeEventSink,
    ): AgentExecution = withContext(NonCancellable) {
        val persisted = withTerminalTransition(execution.id) {
            val committed = executionRepository.commitTurn(
                expected = execution,
                state = executionOutcome.session.toState(conversationKey),
                usage = executionOutcome.usage.toExecutionUsage(),
                output = executionOutcome.output,
                assistantMessageId = eventSink.finalAssistantMessageId,
            ) ?: return@withTerminalTransition currentAfterRejectedTurn(execution)
            logger.atInfo()
                .addKeyValue("event", "execution.token_usage")
                .addKeyValue("input_tokens", executionOutcome.usage.promptTokens)
                .addKeyValue("output_tokens", executionOutcome.usage.completionTokens)
                .addKeyValue("total_tokens", executionOutcome.usage.totalTokens)
                .addKeyValue("cached_input_tokens", executionOutcome.usage.precachedTokens)
                .log("Backend execution token usage")
            eventSink.emitAssistantMessageCompleted(checkNotNull(committed.assistantMessage), committed.assistantMessageCreated)
            committed.execution
        }
        eventSink.emitTerminal(persisted)

        persisted
    }

    private suspend fun persistWaitingOptionExecution(
        execution: AgentExecution,
        executionOutcome: BackendConversationTurnOutcome.WaitingOption,
        conversationKey: AgentConversationKey,
    ): AgentExecution = executionRepository.commitTurn(
        expected = execution,
        state = executionOutcome.session.toState(conversationKey),
        usage = executionOutcome.usage.toExecutionUsage(),
    )?.execution ?: currentAfterRejectedTurn(execution)

    private suspend fun currentAfterRejectedTurn(execution: AgentExecution): AgentExecution {
        val current = currentExecution(execution.id, execution.userId, execution.chatId)
        if (current.cancelRequested || current.status == AgentExecutionStatus.CANCELLING) {
            throw CancellationException("Execution cancellation was committed before the turn.")
        }
        return current
    }

    internal suspend fun <T> withTerminalTransition(executionId: UUID, block: suspend () -> T): T =
        clientThreadRegistry?.withTerminalTransition(executionId, block) ?: block()

    private suspend fun failExecution(
        execution: AgentExecution,
        eventSink: BackendAgentRuntimeEventSink,
        error: Exception,
    ): Nothing {
        val failure = error.toExecutionFailure(execution)
        val cancelled = withContext(NonCancellable) {
            val persisted = markFailed(
                execution = execution,
                errorCode = failure.errorCode,
                errorMessage = failure.errorMessage,
                usage = failure.usage,
            )
            eventSink.emitTerminal(persisted)
            persisted.status == AgentExecutionStatus.CANCELLED
        }
        if (cancelled) throw CancellationException("Execution cancellation won the terminal transition.")
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
