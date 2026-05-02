package ru.souz.backend.execution.service

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.http.BackendV1Exception

internal class AgentExecutionLauncher(
    private val executionScope: CoroutineScope,
    private val finalizer: AgentExecutionFinalizer,
    private val activeJobs: ActiveExecutionJobRegistry = ActiveExecutionJobRegistry(),
) {
    suspend fun startBackgroundExecution(
        execution: AgentExecution,
        eventSink: BackendAgentRuntimeEventSink,
        block: suspend () -> Unit,
    ) {
        val executionJob = executionScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (_: BackendV1Exception) {
                // Background failures are already persisted by the finalizer path.
            }
        }
        executionJob.invokeOnCompletion { cause ->
            executionScope.launch(NonCancellable) {
                activeJobs.unregister(execution.id, executionJob)
                if (cause is CancellationException) {
                    finalizer.finalizeCancelledExecutionIfNeeded(
                        executionId = execution.id,
                        userId = execution.userId,
                        chatId = execution.chatId,
                        eventSink = eventSink,
                    )
                }
            }
        }
        activeJobs.register(execution.id, executionJob)
        executionJob.start()
    }

    suspend fun <T> runTrackedExecution(
        execution: AgentExecution,
        eventSink: BackendAgentRuntimeEventSink,
        block: suspend () -> T,
    ): T = coroutineScope {
        val executionJob = async(start = CoroutineStart.LAZY) {
            block()
        }

        activeJobs.register(execution.id, executionJob)
        executionJob.start()
        try {
            executionJob.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw e
            }
            finalizer.finalizeCancelledExecutionIfNeeded(
                executionId = execution.id,
                userId = execution.userId,
                chatId = execution.chatId,
                eventSink = eventSink,
            )
            throw ExecutionCancelledException
        } finally {
            activeJobs.unregister(execution.id, executionJob)
        }
    }

    suspend fun cancel(executionId: UUID): Boolean = activeJobs.cancel(executionId)
}

internal object ExecutionCancelledException : CancellationException("Agent execution was cancelled.")
