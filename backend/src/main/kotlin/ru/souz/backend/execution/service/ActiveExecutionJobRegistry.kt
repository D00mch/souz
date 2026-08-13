package ru.souz.backend.execution.service

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ActiveExecutionJobRegistry {
    private val mutex = Mutex()
    private val jobs = LinkedHashMap<UUID, Job>()

    suspend fun registerAndStart(executionId: UUID, job: Job) = mutex.withLock {
        jobs[executionId] = job
        job.start()
    }

    suspend fun unregister(executionId: UUID, job: Job) = mutex.withLock {
        if (jobs[executionId] == job) {
            jobs.remove(executionId)
        }
    }

    suspend fun contains(executionId: UUID): Boolean = mutex.withLock {
        executionId in jobs
    }

    suspend fun join(executionId: UUID): Boolean {
        val job = mutex.withLock { jobs[executionId] } ?: return false
        job.join()
        return true
    }

    suspend fun cancel(
        executionId: UUID,
        reason: String = "Execution cancelled by user request.",
    ): Boolean = mutex.withLock {
        val job = jobs[executionId] ?: return@withLock false
        job.cancel(CancellationException(reason))
        true
    }
}
