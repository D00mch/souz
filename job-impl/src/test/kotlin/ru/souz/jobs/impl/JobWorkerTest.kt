package ru.souz.jobs.impl

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ru.souz.jobs.JobRun
import ru.souz.jobs.JobStatus

class JobWorkerTest {
    private val fastTiming = JobWorkerTiming(pollMillis = 10, leaseSeconds = 3, heartbeatMillis = 20, retrySeconds = 30)

    @Test
    fun `cancelled active handler stops at heartbeat and worker continues to the next job`() = jobTest(fastTiming) { _, service ->
        val cancelled = service.create(title = "Cancel me")
        val next = service.create(title = "Next job")
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val nextStarted = CompletableDeferred<Unit>()
        val worker = service.startWorker(this) { run ->
            if (run.jobId == cancelled.id) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            } else {
                assertEquals(next.id, run.jobId)
                nextStarted.complete(Unit)
            }
        }
        assertSame(worker, service.startWorker(this) { error("First worker keeps its handler") })
        withTimeout(5_000) { started.await() }
        assertEquals(JobStatus.CANCELLED, service.cancelJob("owner", cancelled.id)?.status)
        withTimeout(5_000) { stopped.await(); nextStarted.await() }
        awaitStatus(service, next.id, JobStatus.SUCCEEDED)
        assertEquals(JobStatus.CANCELLED, service.listJobs("owner").first { it.id == cancelled.id }.status)
        assertTrue(worker.isActive)
    }

    @Test
    fun `worker reuse waits for shutdown cleanup and interrupted occurrence recovers without refunding attempt`() = jobTest(fastTiming) { db, service ->
        val job = service.create()
        val started = CompletableDeferred<JobRun>()
        val cleaningUp = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val recovered = CompletableDeferred<JobRun>()
        try {
            val first = service.startWorker(this) { run ->
                started.complete(run)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaningUp.complete(Unit)
                        releaseCleanup.await()
                    }
                }
            }
            val original = withTimeout(5_000) { started.await() }
            first.cancel()
            withTimeout(5_000) { cleaningUp.await() }
            assertSame(first, service.startWorker(this) { error("Cleanup must finish before replacement") })
            releaseCleanup.complete(Unit)
            withTimeout(5_000) { first.join() }
            val interrupted = service.listJobs("owner").single()
            assertEquals(JobStatus.RUNNING, interrupted.status)
            assertEquals(1, interrupted.attempts)
            db.execute("update jobs set lease_until = clock_timestamp() - interval '1 second' where id = ?", job.id)
            val second = service.startWorker(this) { recovered.complete(it) }
            assertNotSame(first, second)
            val replay = withTimeout(5_000) { recovered.await() }
            assertEquals(original.jobId, replay.jobId)
            assertEquals(original.scheduledAt, replay.scheduledAt)
            assertEquals(2, replay.attempt)
            awaitStatus(service, job.id, JobStatus.SUCCEEDED)
        } finally {
            releaseCleanup.complete(Unit)
        }
    }

    @Test
    fun `handler failure and timeout schedule retries while the worker continues to the next job`() {
        val failures = listOf<suspend () -> Unit>(
            { error("handler failed") },
            { withTimeout(50) { awaitCancellation() } },
        )
        for (fail in failures) jobTest(fastTiming) { _, service ->
            val failed = service.create(title = "Failure")
            val next = service.create(title = "Success")
            val worker = service.startWorker(this) { run ->
                if (run.jobId == failed.id) fail() else assertEquals(next.id, run.jobId)
            }
            awaitStatus(service, next.id, JobStatus.SUCCEEDED)
            val retrying = service.listJobs("owner").first { it.id == failed.id }
            assertEquals(JobStatus.PENDING, retrying.status)
            assertEquals(1, retrying.attempts)
            assertNotNull(retrying.lastError)
            assertNotNull(retrying.lastFinishedAt)
            assertTrue(worker.isActive)
        }
    }

    @Test
    fun `heartbeat database failure cancels handler preserves attempt and permits other work and recovery`() = jobTest(fastTiming) { db, service ->
        val interrupted = service.create(title = "Renewal fails")
        val ready = service.create(title = "Unrelated work")
        db.execute("""
            create function reject_job_renewal() returns trigger language plpgsql as ${'$'}${'$'}
            begin
              if new.id = '${interrupted.id}'::uuid and new.status = 'RUNNING' and old.status = 'RUNNING'
                and new.lease_token = old.lease_token then raise exception 'renewal unavailable'; end if;
              return new;
            end;
            ${'$'}${'$'}
        """.trimIndent())
        db.execute("create trigger reject_job_renewal before update on jobs for each row execute function reject_job_renewal()")
        val started = CompletableDeferred<JobRun>()
        val stopped = CompletableDeferred<Unit>()
        val otherStarted = CompletableDeferred<Unit>()
        val retried = CompletableDeferred<JobRun>()
        val worker = service.startWorker(this) { run ->
            if (run.jobId == interrupted.id && run.attempt == 1) {
                started.complete(run)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            } else if (run.jobId == interrupted.id) {
                retried.complete(run)
            } else {
                assertEquals(ready.id, run.jobId)
                otherStarted.complete(Unit)
            }
        }
        val original = withTimeout(5_000) { started.await() }
        withTimeout(5_000) { stopped.await(); otherStarted.await() }
        awaitStatus(service, ready.id, JobStatus.SUCCEEDED)
        assertEquals(1, service.listJobs("owner").first { it.id == interrupted.id }.attempts)
        assertTrue(worker.isActive)
        db.execute("drop trigger reject_job_renewal on jobs")
        db.execute("""
            update jobs set available_at = clock_timestamp() - interval '1 second',
              lease_until = case when status = 'RUNNING' then clock_timestamp() - interval '1 second' else lease_until end
            where id = ?
        """.trimIndent(), interrupted.id)
        val replay = withTimeout(5_000) { retried.await() }
        assertEquals(original.scheduledAt, replay.scheduledAt)
        assertEquals(2, replay.attempt)
        awaitStatus(service, interrupted.id, JobStatus.SUCCEEDED)
    }

    @Test
    fun `failed outcome persistence leaves successful handler claim for lease recovery`() = jobTest { db, service ->
        val job = service.create()
        db.execute("""
            create function reject_job_finish() returns trigger language plpgsql as ${'$'}${'$'}
            begin
              if new.status = 'SUCCEEDED' then raise exception 'outcome unavailable'; end if;
              return new;
            end;
            ${'$'}${'$'}
        """.trimIndent())
        db.execute("create trigger reject_job_finish before update on jobs for each row execute function reject_job_finish()")
        var original: JobRun? = null
        assertFailsWith<SQLException> { service.processNext { original = it } }
        val interrupted = service.listJobs("owner").single()
        assertEquals(JobStatus.RUNNING, interrupted.status)
        assertEquals(1, interrupted.attempts)
        db.execute("drop trigger reject_job_finish on jobs")
        db.execute("update jobs set lease_until = clock_timestamp() - interval '1 second' where id = ?", job.id)
        assertTrue(service.processNext { replay ->
            assertEquals(original?.jobId, replay.jobId)
            assertEquals(original?.scheduledAt, replay.scheduledAt)
            assertEquals(2, replay.attempt)
        })
        assertEquals(JobStatus.SUCCEEDED, service.listJobs("owner").single().status)
    }

    private suspend fun awaitStatus(service: PostgresJobService, id: java.util.UUID, status: JobStatus) {
        withTimeout(5_000) {
            while (service.listJobs("owner").first { it.id == id }.status != status) delay(10)
        }
    }
}
