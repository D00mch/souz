package ru.souz.backend.execution.service

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.souz.backend.agent.runtime.BackendAgentRuntimeEventSink
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.isActive
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.http.BackendV1Exception

internal class AgentExecutionLauncher(
    private val executionScope: CoroutineScope,
    private val finalizer: AgentExecutionFinalizer,
    private val activeJobs: ActiveExecutionJobRegistry = ActiveExecutionJobRegistry(),
    private val executionRepository: AgentExecutionRepository? = null,
    private val clientThreadRegistry: ClientThreadRuntimeRegistry? = null,
    private val leaseRefreshInterval: Duration = ClientThreadRuntimeRegistry.LEASE_REFRESH_INTERVAL,
) {
    suspend fun launchRegistered(
        execution: AgentExecution,
        eventSink: BackendAgentRuntimeEventSink,
        block: suspend () -> Unit,
    ): Job {
        val startSignal = CompletableDeferred<Unit>()
        val lifecycleReady = CompletableDeferred<Unit>()
        lateinit var executionJob: Job
        executionJob = executionScope.launch(start = CoroutineStart.LAZY) {
            lifecycleReady.complete(Unit)
            var leaseJob: Job? = null
            try {
                startSignal.await()
                leaseJob = startClientThreadLeaseRefresh(this, execution)
                block()
            } catch (_: BackendV1Exception) {
                // Background failures are already persisted by the finalizer path.
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    finalizer.finalizeCancelledExecutionIfNeeded(
                        executionId = execution.id,
                        userId = execution.userId,
                        chatId = execution.chatId,
                        eventSink = eventSink,
                    )
                }
                throw cancelled
            } finally {
                withContext(NonCancellable) {
                    try {
                        leaseJob?.cancelAndJoin()
                    } finally {
                        activeJobs.unregister(execution.id, executionJob)
                    }
                }
            }
        }
        activeJobs.registerAndStart(execution.id, executionJob)
        executionJob.invokeOnCompletion { lifecycleReady.complete(Unit) }
        lifecycleReady.await()
        if (executionJob.isCompleted && activeJobs.contains(execution.id)) {
            withContext(NonCancellable) {
                try {
                    if (executionJob.isCancelled) {
                        finalizer.finalizeCancelledExecutionIfNeeded(
                            executionId = execution.id,
                            userId = execution.userId,
                            chatId = execution.chatId,
                            eventSink = eventSink,
                        )
                    }
                } finally {
                    activeJobs.unregister(execution.id, executionJob)
                }
            }
        } else {
            startSignal.complete(Unit)
        }
        return executionJob
    }

    suspend fun cancel(executionId: UUID): Boolean = activeJobs.cancel(executionId)

    private suspend fun startClientThreadLeaseRefresh(
        owningScope: CoroutineScope,
        execution: AgentExecution,
    ): Job? {
        val repository = executionRepository ?: return null
        val registry = clientThreadRegistry ?: return null
        if (!registry.contains(execution.id)) return null
        val owner = registry.runtimeOwner
        return owningScope.launch {
            var leaseExpiresAt = execution.runtimeLeaseUntil ?: ClientThreadRuntimeRegistry.leaseUntil()
            while (isActive) {
                delay(leaseRefreshInterval.toMillis())
                if (!Instant.now().isBefore(leaseExpiresAt)) {
                    activeJobs.cancel(
                        execution.id,
                        reason = "Client thread runtime lease expired before it could be renewed.",
                    )
                    return@launch
                }
                try {
                    val nextLeaseUntil = ClientThreadRuntimeRegistry.leaseUntil()
                    val refreshed = repository.refreshClientThreadLease(
                        userId = execution.userId,
                        chatId = execution.chatId,
                        executionId = execution.id,
                        runtimeOwner = owner,
                        leaseUntil = nextLeaseUntil,
                    )
                    if (refreshed == null) {
                        val current = repository.getByChat(execution.userId, execution.chatId, execution.id)
                        if (current != null && !current.status.isActive()) {
                            return@launch
                        }
                        activeJobs.cancel(
                            execution.id,
                            reason = "Client thread runtime lease is no longer owned by this execution.",
                        )
                        return@launch
                    }
                    leaseExpiresAt = refreshed.runtimeLeaseUntil ?: nextLeaseUntil
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (!Instant.now().isBefore(leaseExpiresAt)) {
                        activeJobs.cancel(
                            execution.id,
                            reason = "Client thread runtime lease refresh failed until the lease expired.",
                        )
                        return@launch
                    }
                }
            }
        }
    }
}
