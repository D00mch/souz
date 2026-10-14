package ru.souz.jobs

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/** Owner-scoped job operations, independent of the host and persistence implementation. */
interface JobService {
    suspend fun createJob(userId: String, request: CreateJobRequest): JobInfo
    /** Newest first; the cursor is the last job ID from the preceding page. */
    suspend fun listJobs(userId: String, limit: Int = 20, beforeJobId: UUID? = null, includeInactive: Boolean = true): JobPage
    /** Latest three exhausted occurrences per requested job, newest first; accepts at most 100 owner-scoped IDs. */
    suspend fun listFailedOccurrences(userId: String, jobIds: Set<UUID>): List<FailedJobOccurrence>

    /** Returns null for an unknown owner/job pair; terminal jobs are returned unchanged. */
    suspend fun cancelJob(userId: String, jobId: UUID): JobInfo?
}

data class JobPage(val items: List<JobInfo>, val nextCursor: UUID?)

data class CreateJobRequest(
    val title: String,
    val payload: JsonNode,
    val schedule: JobSchedule = JobSchedule.Once(),
    /** Keep future cron occurrences after exhausting this occurrence's retries. */
    val continueAfterFailure: Boolean = false,
)

sealed interface JobSchedule {
    /** A null timestamp means immediately; past timestamps are also eligible immediately. */
    data class Once(val runAt: Instant? = null) : JobSchedule
    data class Cron(val expression: String, val timeZone: String) : JobSchedule
}

enum class JobStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class JobInfo(
    val id: UUID,
    val userId: String,
    val title: String,
    val payload: JsonNode,
    val schedule: JobSchedule,
    val status: JobStatus,
    val scheduledAt: Instant,
    val availableAt: Instant?,
    val attempts: Int,
    val createdAt: Instant,
    val lastFinishedAt: Instant?,
    val lastError: String?,
    val continueAfterFailure: Boolean = false,
)

/** A terminal dispatch failure, retained even when a later cron occurrence succeeds. */
data class FailedJobOccurrence(
    val jobId: UUID,
    val scheduledAt: Instant,
    val attempts: Int,
    val finishedAt: Instant,
    val error: String,
)

/** Delivery is at least once. Use (jobId, scheduledAt) to deduplicate an occurrence's effects. */
data class JobRun(
    val jobId: UUID,
    val userId: String,
    val title: String,
    val payload: JsonNode,
    val scheduledAt: Instant,
    val attempt: Int,
)
