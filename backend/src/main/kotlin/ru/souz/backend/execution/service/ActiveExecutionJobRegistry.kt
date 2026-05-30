package ru.souz.backend.execution.service

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ActiveExecutionJobRegistry {
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
