package ru.souz.jobs.impl

import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.FailedJobOccurrence
import ru.souz.jobs.JobInfo
import ru.souz.jobs.JobPage
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

    constructor(dataSource: DataSource, pollIntervalMillis: Long) : this(
        dataSource, JobWorkerTiming(pollMillis = pollIntervalMillis),
    ) {
        require(pollIntervalMillis > 0) { "Job poll interval must be positive." }
    }

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

    override suspend fun listJobs(userId: String, limit: Int, beforeJobId: UUID?, includeInactive: Boolean): JobPage {
        require(limit in 1..100) { "Job page limit must be between 1 and 100." }
        val rows = store.list(userId, limit + 1, beforeJobId, includeInactive)
        val items = rows.take(limit)
        return JobPage(items, items.lastOrNull()?.id?.takeIf { rows.size > limit })
    }

    override suspend fun listFailedOccurrences(userId: String, jobIds: Set<UUID>): List<FailedJobOccurrence> {
        if (jobIds.isEmpty()) return emptyList()
        require(jobIds.size <= 100) { "Request failures for at most 100 jobs." }
        return store.listFailures(userId, jobIds)
    }

    override suspend fun cancelJob(userId: String, jobId: UUID): JobInfo? = store.cancel(userId, jobId)

    /**
     * One sequential worker per service instance. Before each claim, [admit] suspends until the host can start a job
     * and returns owners whose due jobs stay pending meanwhile. Repeated starts retain the original scope and callbacks.
     */
    suspend fun startWorker(
        scope: CoroutineScope,
        admit: suspend () -> Set<String> = { emptySet() },
        handler: suspend (JobRun) -> Unit,
    ): Job = startup.withLock {
        worker?.takeUnless { it.isCompleted } ?: scope.launch {
            while (isActive) {
                try {
                    if (processNext(admit(), handler)) continue
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.warn("Job polling failed category={}", error.javaClass.simpleName)
                }
                delay(timing.pollMillis)
            }
        }.also { worker = it }
    }

    /** How long the earliest due pending job has waited for a claim, in PostgreSQL time; zero when none is due. */
    suspend fun oldestDueWait(): Duration = store.oldestDueWait()

    @Suppress("SuspendFunSwallowedCancellation") // ensureActive propagates worker cancellation; handler cancellation is retried.
    internal suspend fun processNext(skippedOwners: Set<String> = emptySet(), handler: suspend (JobRun) -> Unit): Boolean {
        val claim = store.claim(skippedOwners) ?: return false
        val job = claim.job
        val error = try {
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
            null
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            cancelled.message ?: cancelled.javaClass.simpleName
        } catch (_: JobLeaseLost) {
            return true
        } catch (failure: Exception) {
            failure.message ?: failure.javaClass.simpleName
        }
        if (!store.finish(claim, error)) logger.info("Job ownership lost jobId={} attempt={}", job.id, job.attempts)
        return true
    }
}

private class JobLeaseLost : IllegalStateException("Job lease lost.")
