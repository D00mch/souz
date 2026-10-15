package ru.souz.jobs.impl

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.jobs.FailedJobOccurrence
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobInfo
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobStatus

internal data class JobClaim(val job: JobInfo, val token: UUID)

internal class PostgresJobStore(private val dataSource: DataSource, private val timing: JobWorkerTiming) {
    suspend fun create(userId: String, request: CreateJobRequest): JobInfo = connection { connection ->
        val now = connection.now()
        val once = request.schedule as? JobSchedule.Once
        val cron = request.schedule as? JobSchedule.Cron
        val scheduledAt = cron?.let { nextCronTime(it.expression, it.timeZone, now) } ?: once?.runAt ?: now
        connection.query(
            """
            insert into jobs(id, user_id, title, payload, run_at, cron, time_zone, scheduled_at, available_at, status, continue_after_failure)
            values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, 'PENDING', ?) returning *
            """.trimIndent(),
            UUID.randomUUID(), userId, request.title, request.payload.toString(), once?.runAt,
            cron?.expression, cron?.timeZone, scheduledAt, scheduledAt, request.continueAfterFailure,
        ) { check(next()); jobInfo() }
    }

    suspend fun list(userId: String, limit: Int, beforeJobId: UUID?, includeInactive: Boolean): List<JobInfo> = connection { connection ->
        val active = if (includeInactive) "" else "and status in ('PENDING', 'RUNNING')"
        // An unknown or foreign cursor matches no anchor row, so the page is empty.
        val before = if (beforeJobId == null) "" else
            "and (created_at, id) < (select created_at, id from jobs where user_id = ? and id = ?)"
        val cursorParameters: Array<Any> = if (beforeJobId == null) emptyArray() else arrayOf(userId, beforeJobId)
        connection.query(
            """
            select * from jobs where user_id = ? $active $before
            order by created_at desc, id desc limit ?
            """.trimIndent(), userId, *cursorParameters, limit,
        ) {
            buildList { while (next()) add(jobInfo()) }
        }
    }

    suspend fun cancel(userId: String, jobId: UUID): JobInfo? = connection { connection ->
        connection.query(
            """
            update jobs set
              status = case when status in ('PENDING', 'RUNNING') then 'CANCELLED' else status end,
              available_at = case when status in ('PENDING', 'RUNNING') then null else available_at end,
              lease_token = null, lease_until = null
            where user_id = ? and id = ? returning *
            """.trimIndent(), userId, jobId,
        ) { if (next()) jobInfo() else null }
    }

    suspend fun claim(): JobClaim? = transaction { connection ->
        // Lock expired claims once; recording failure and advancing recurrence commit together.
        val expired = connection.query(
            """
            select * from jobs where status = 'RUNNING' and lease_until <= statement_timestamp()
            for update skip locked
            """.trimIndent(),
        ) { buildList { while (next()) add(jobInfo()) } }
        expired.forEach { job ->
            complete(connection, job, "Worker lease expired.", connection.now(), job.availableAt)
        }
        val token = UUID.randomUUID()
        connection.query(
            """
            update jobs set status = 'RUNNING', attempts = attempts + 1,
              lease_token = ?, lease_until = clock_timestamp() + ? * interval '1 second'
            where id = (
              select id from jobs
              where status = 'PENDING' and available_at <= statement_timestamp() and attempts < 3
              order by available_at, id limit 1 for update skip locked
            ) returning *
            """.trimIndent(), token, timing.leaseSeconds,
        ) { if (next()) JobClaim(jobInfo(), token) else null }
    }

    suspend fun renew(claim: JobClaim): Boolean = lockedConnection(claim.job.id) { connection ->
        connection.update(
            "update jobs set lease_until = clock_timestamp() + ? * interval '1 second' where $OWNED",
            timing.leaseSeconds, claim.job.id, claim.token,
        ) == 1
    }

    suspend fun finish(claim: JobClaim, error: String? = null): Boolean = lockedConnection(claim.job.id) { connection ->
        val owned = connection.query("select id from jobs where $OWNED", claim.job.id, claim.token) { next() }
        if (owned) complete(connection, claim.job, error, connection.now())
        owned
    }

    private fun complete(
        connection: Connection, job: JobInfo, error: String?, now: Instant, retryAt: Instant? = null,
    ) {
        val retry = error != null && job.attempts < 3
        val exhausted = error != null && !retry
        val cron = job.schedule as? JobSchedule.Cron
        val next = if (cron != null && (error == null || exhausted && job.continueAfterFailure)) {
            nextCronTime(cron.expression, cron.timeZone, maxOf(now, job.scheduledAt))
        } else null
        if (exhausted) {
            connection.update(
                """
                insert into job_occurrence_failures(job_id, scheduled_at, attempts, finished_at, error)
                values (?, ?, ?, ?, ?) on conflict (job_id, scheduled_at) do nothing
                """.trimIndent(), job.id, job.scheduledAt, job.attempts, now, error,
            )
        }
        val status = when {
            next != null || retry -> JobStatus.PENDING
            error == null -> JobStatus.SUCCEEDED
            else -> JobStatus.FAILED
        }
        connection.update(
            """
            update jobs set status = ?, scheduled_at = coalesce(?, scheduled_at), available_at = ?,
              attempts = ?, last_finished_at = ?, last_error = ?, lease_token = null, lease_until = null
            where id = ?
            """.trimIndent(), status.name, next, next ?: (retryAt ?: now.plusSeconds(timing.retrySeconds)).takeIf { retry },
            if (next != null) 0 else job.attempts, now, error, job.id,
        )
    }

    suspend fun listFailures(userId: String, jobIds: Set<UUID>): List<FailedJobOccurrence> = connection { connection ->
        connection.query(
            """
            select f.* from jobs j cross join lateral (
              select * from job_occurrence_failures where job_id = j.id order by scheduled_at desc limit 3
            ) f
            where j.user_id = ? and j.id = any(?) order by f.scheduled_at desc, f.job_id
            """.trimIndent(), userId, connection.createArrayOf("uuid", jobIds.toTypedArray()),
        ) {
            buildList {
                while (next()) add(FailedJobOccurrence(
                    getObject("job_id", UUID::class.java), instant("scheduled_at")!!, getInt("attempts"),
                    instant("finished_at")!!, getString("error"),
                ))
            }
        }
    }

    private suspend fun lockedConnection(jobId: UUID, block: (Connection) -> Boolean): Boolean = transaction { connection ->
        // Acquire the lock before checking ownership: the wait itself can outlast the lease.
        connection.query("select id from jobs where id = ? for update", jobId) { next() }
        block(connection)
    }

    private suspend fun <T> transaction(block: (Connection) -> T): T = connection { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    private suspend fun <T> connection(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use(block)
    }

    private companion object {
        const val OWNED = "id = ? and lease_token = ? and status = 'RUNNING' and lease_until > clock_timestamp()"
    }
}

private val jobMapper = jacksonObjectMapper()

private fun ResultSet.jobInfo(): JobInfo = JobInfo(
    id = getObject("id", UUID::class.java), userId = getString("user_id"), title = getString("title"),
    payload = jobMapper.readTree(getString("payload")),
    schedule = getString("cron")?.let { JobSchedule.Cron(it, getString("time_zone")) }
        ?: JobSchedule.Once(instant("run_at")),
    status = JobStatus.valueOf(getString("status")), scheduledAt = instant("scheduled_at")!!,
    availableAt = instant("available_at"), attempts = getInt("attempts"), createdAt = instant("created_at")!!,
    lastFinishedAt = instant("last_finished_at"), lastError = getString("last_error"),
    continueAfterFailure = getBoolean("continue_after_failure"),
)

private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

private fun Connection.now(): Instant = query("select clock_timestamp()") {
    check(next()); getObject(1, OffsetDateTime::class.java).toInstant()
}

private fun <T> Connection.query(sql: String, vararg values: Any?, read: ResultSet.() -> T): T =
    statement(sql, values) { executeQuery().use(read) }

private fun Connection.update(sql: String, vararg values: Any?): Int = statement(sql, values) { executeUpdate() }

private fun <T> Connection.statement(sql: String, values: Array<out Any?>, block: PreparedStatement.() -> T): T =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value ->
            statement.setObject(index + 1, if (value is Instant) value.atOffset(ZoneOffset.UTC) else value)
        }
        block(statement)
    }
