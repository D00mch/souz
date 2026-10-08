package ru.souz.jobs.impl

import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobStatus

class PostgresJobStoreTest {
    @Test
    fun `claim skips future jobs and locked rows then takes oldest available`() = jobTest { db, service ->
        service.create(schedule = JobSchedule.Once(Instant.parse("2035-01-01T00:00:00Z")))
        val oldest = service.create(schedule = JobSchedule.Once(Instant.parse("2020-01-01T00:00:00Z")))
        val later = service.create(schedule = JobSchedule.Once(Instant.parse("2020-01-02T00:00:00Z")))
        db.connection.use { lock ->
            lock.autoCommit = false
            lock.prepareStatement("select id from jobs where id = ? for update").use { statement ->
                statement.setObject(1, oldest.id)
                statement.executeQuery().use { assertTrue(it.next()) }
            }
            assertEquals(later.id, service.store.claim()?.job?.id)
            lock.rollback()
        }
        assertEquals(oldest.id, service.store.claim()?.job?.id)
        assertNull(service.store.claim())
    }

    @Test
    fun `concurrent instances claim distinct occurrences once`() = jobTest { db, first ->
        val second = PostgresJobService(db)
        val jobs = (1..8).map { first.create(title = "Job $it") }
        val claims = coroutineScope {
            (1..8).map { index ->
                async(Dispatchers.IO) { (if (index % 2 == 0) first else second).store.claim() }
            }.awaitAll()
        }.map { assertNotNull(it) }
        assertEquals(jobs.map { it.id }.toSet(), claims.map { it.job.id }.toSet())
        assertTrue(claims.all { it.job.attempts == 1 })
        assertNull(first.store.claim())
    }

    @Test
    fun `expired ownership is reclaimed with stable occurrence identity and stale writes are fenced`() = jobTest { db, service ->
        service.create()
        val first = assertNotNull(service.store.claim())
        assertTrue(service.store.renew(first))
        db.execute("update jobs set lease_until = clock_timestamp() - interval '1 second' where id = ?", first.job.id)
        assertFalse(service.store.renew(first))
        assertFalse(service.store.finish(first))
        val second = assertNotNull(PostgresJobService(db).store.claim())
        assertEquals(first.job.id, second.job.id)
        assertEquals(first.job.scheduledAt, second.job.scheduledAt)
        assertEquals(2, second.job.attempts)
        assertNotEquals(first.token, second.token)
        assertFalse(service.store.renew(first))
        assertFalse(service.store.finish(first, "stale failure"))
        assertTrue(service.store.finish(second))
        assertEquals(JobStatus.SUCCEEDED, service.listJobs("owner").single().status)
    }

    @Test
    fun `renew rejects lease that expires while waiting for an unchanged locked row`() =
        assertLeaseExpiryWhileLocked { renew(it) }

    @Test
    fun `finish rejects lease that expires while waiting for an unchanged locked row`() =
        assertLeaseExpiryWhileLocked { finish(it) }

    private fun assertLeaseExpiryWhileLocked(update: suspend PostgresJobStore.(JobClaim) -> Boolean) =
        jobTest(JobWorkerTiming(leaseSeconds = 3)) { db, service ->
            service.create()
            val claim = assertNotNull(service.store.claim())
            db.connection.use { lock ->
                lock.autoCommit = false
                val (expiresAt, blocker) = lock.prepareStatement(
                    "select lease_until, pg_backend_pid() from jobs where id = ? for update",
                ).use { statement ->
                    statement.setObject(1, claim.job.id)
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getObject(1, OffsetDateTime::class.java).toInstant() to rows.getInt(2)
                    }
                }
                coroutineScope {
                    val pending = async(Dispatchers.IO) { service.store.update(claim) }
                    try {
                        withTimeout(2_000) {
                            db.connection.use { observer ->
                                observer.prepareStatement(
                                    "select exists (select 1 from pg_stat_activity where ? = any(pg_blocking_pids(pid)))",
                                ).use { statement ->
                                    statement.setInt(1, blocker)
                                    while (!statement.executeQuery().use { it.next(); it.getBoolean(1) }) delay(10)
                                }
                            }
                        }
                        assertTrue(db.databaseNow() < expiresAt, "The operation must block before lease expiry")
                        withTimeout(5_000) {
                            while (db.databaseNow() <= expiresAt) delay(10)
                        }
                        assertFalse(pending.isCompleted)
                    } finally {
                        lock.rollback()
                    }
                    assertFalse(withTimeout(5_000) { pending.await() })
                }
            }
            assertEquals(claim.job, service.listJobs("owner").single())
            val recovered = assertNotNull(service.store.claim())
            assertEquals(claim.job.id, recovered.job.id)
            assertEquals(claim.job.scheduledAt, recovered.job.scheduledAt)
            assertEquals(2, recovered.job.attempts)
            assertNotEquals(claim.token, recovered.token)
            assertTrue(service.store.finish(recovered))
        }

    @Test
    fun `two retries retain occurrence time and the third failure stops recurring jobs`() = jobTest { db, service ->
        val job = service.create(schedule = JobSchedule.Cron("* * * * *", "UTC"))
        db.execute("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?", job.id)
        for (attempt in 1..3) {
            val claim = assertNotNull(service.store.claim())
            assertEquals(attempt, claim.job.attempts)
            assertEquals(job.scheduledAt, claim.job.scheduledAt)
            val before = db.databaseNow()
            assertTrue(service.store.finish(claim, "failure-$attempt"))
            val current = service.listJobs("owner").single()
            assertEquals("failure-$attempt", current.lastError)
            assertEquals(attempt, current.attempts)
            if (attempt < 3) {
                assertEquals(JobStatus.PENDING, current.status)
                assertTrue(assertNotNull(current.availableAt) >= before.plusSeconds(30))
                assertNull(service.store.claim())
                db.execute("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?", job.id)
            } else {
                assertEquals(JobStatus.FAILED, current.status)
                assertNull(current.availableAt)
                assertNotNull(current.lastFinishedAt)
                assertEquals(current, service.cancelJob("owner", job.id))
                assertNull(service.store.claim())
            }
        }
    }

    @Test
    fun `locked exhausted claim does not block ready work and fails after its lock releases`() = jobTest { db, service ->
        val exhausted = service.create()
        repeat(3) { attempt ->
            assertEquals(attempt + 1, assertNotNull(service.store.claim()).job.attempts)
            db.execute("update jobs set lease_until = clock_timestamp() - interval '1 second' where id = ?", exhausted.id)
        }
        val ready = service.create()
        db.connection.use { lock ->
            lock.autoCommit = false
            lock.prepareStatement("select id from jobs where id = ? for update").use { statement ->
                statement.setObject(1, exhausted.id)
                statement.executeQuery().use { assertTrue(it.next()) }
            }
            val claimed = coroutineScope {
                val pending = async(Dispatchers.IO) { service.store.claim() }
                try {
                    withTimeout(5_000) { pending.await() }
                } finally {
                    lock.rollback()
                }
            }
            assertEquals(ready.id, claimed?.job?.id)
        }
        assertNull(service.store.claim())
        val failed = service.listJobs("owner").first { it.id == exhausted.id }
        assertEquals(JobStatus.FAILED, failed.status)
        assertEquals(3, failed.attempts)
        assertNotNull(failed.lastError)
        assertNotNull(failed.lastFinishedAt)
    }

    @Test
    fun `cancellation racing completion leaves one terminal outcome and fences later writes`() = jobTest { _, service ->
        repeat(12) {
            val job = service.create()
            val claim = assertNotNull(service.store.claim())
            val (cancelled, completed) = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val finishing = async(Dispatchers.IO) { start.await(); service.store.finish(claim) }
                val cancelling = async(Dispatchers.IO) { start.await(); service.cancelJob("owner", job.id) }
                start.complete(Unit)
                cancelling.await() to finishing.await()
            }
            val terminal = service.listJobs("owner").first { it.id == job.id }
            assertEquals(if (completed) JobStatus.SUCCEEDED else JobStatus.CANCELLED, terminal.status)
            assertEquals(terminal, cancelled)
            assertFalse(service.store.renew(claim))
            assertFalse(service.store.finish(claim, "late failure"))
        }
    }
}
