package ru.souz.jobs.impl

import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobInfo
import ru.souz.jobs.JobRun
import ru.souz.jobs.JobService

internal data class JobWorkerTiming(
    val pollMillis: Long = 5_000,
    val leaseSeconds: Long = 180,
    val heartbeatMillis: Long = 30_000,
    val retrySeconds: Long = 30,
)

class PostgresJobService internal constructor(dataSource: DataSource, private val timing: JobWorkerTiming) : JobService {
    constructor(dataSource: DataSource) : this(dataSource, JobWorkerTiming())

    internal val store = PostgresJobStore(dataSource, timing)
    private val logger = LoggerFactory.getLogger(PostgresJobService::class.java)
    private val startup = Mutex()
    private var worker: Job? = null

    override suspend fun createJob(userId: String, request: CreateJobRequest): JobInfo {
        require(userId.isNotBlank()) { "Job owner is required." }
        require(request.title.isNotBlank()) { "Job title is required." }
        require(request.payload.isObject) { "Job payload must be a JSON object." }
        return store.create(userId, request)
    }

    override suspend fun listJobs(userId: String): List<JobInfo> = store.list(userId)

    override suspend fun cancelJob(userId: String, jobId: UUID): JobInfo? = store.cancel(userId, jobId)

    /** One sequential worker per service instance. Repeated starts retain the original scope and handler. */
    suspend fun startWorker(scope: CoroutineScope, handler: suspend (JobRun) -> Unit): Job = startup.withLock {
        worker?.takeUnless { it.isCompleted } ?: scope.launch {
            while (isActive) {
                try {
                    if (processNext(handler)) continue
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.warn("Job polling failed category={}", error.javaClass.simpleName)
                }
                delay(timing.pollMillis)
            }
        }.also { worker = it }
    }

    internal suspend fun processNext(handler: suspend (JobRun) -> Unit): Boolean {
        val claim = store.claim() ?: return false
        val job = claim.job
        var error: String? = null
        try {
            coroutineScope {
                val heartbeat = launch {
                    while (isActive) {
                        delay(timing.heartbeatMillis)
                        if (!store.renew(claim)) throw JobLeaseLost()
                    }
                }
                try {
                    handler(JobRun(job.id, job.userId, job.title, job.payload, job.scheduledAt, job.attempts))
                } finally {
                    heartbeat.cancelAndJoin()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: JobLeaseLost) {
            return true
        } catch (failure: Exception) {
            error = failure.message ?: failure.javaClass.simpleName
        }
        if (!store.finish(claim, error)) logger.info("Job ownership lost jobId={} attempt={}", job.id, job.attempts)
        return true
    }
}

private class JobLeaseLost : IllegalStateException("Job lease lost.")
