package ru.souz.jobs.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobStatus

class JobOccurrenceFailureTest {
    @Test
    fun `transient start failures retry the same occurrence before advancing cron`() = jobTest { db, service ->
        val job = service.create(schedule = JobSchedule.Cron("* * * * *", "UTC"), continueAfterFailure = true)
        repeat(2) { index ->
            db.execute("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?", job.id)
            assertTrue(service.processNext { run ->
                assertEquals(job.scheduledAt, run.scheduledAt)
                assertEquals(index + 1, run.attempt)
                if (index == 0) error("temporary start failure")
            })
            val current = service.listJobs("owner").single()
            assertEquals(JobStatus.PENDING, current.status)
            assertEquals(if (index == 0) 1 else 0, current.attempts)
        }
        assertTrue(service.listJobs("owner").single().scheduledAt > job.scheduledAt)
        assertTrue(service.listFailedOccurrences("owner").isEmpty())
    }

    @Test
    fun `exhausted failures and expired final leases are recorded while future cron occurrences survive`() {
        for (expiredLease in listOf(false, true)) jobTest { db, service ->
            val job = service.create(schedule = JobSchedule.Cron("* * * * *", "UTC"), continueAfterFailure = true)
            repeat(3) { index ->
                db.execute("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?", job.id)
                val claim = assertNotNull(service.store.claim())
                assertEquals(index + 1, claim.job.attempts)
                assertEquals(job.scheduledAt, claim.job.scheduledAt)
                if (expiredLease) {
                    db.execute("update jobs set lease_until = clock_timestamp() - interval '1 second' where id = ?", job.id)
                } else {
                    assertTrue(service.store.finish(claim, "start unavailable"))
                }
            }
            service.store.claim() // Recovers the final expired lease, if any.
            val pending = service.listJobs("owner").single()
            assertEquals(JobStatus.PENDING, pending.status)
            assertEquals(0, pending.attempts)
            assertTrue(pending.scheduledAt > job.scheduledAt)
            val failure = service.listFailedOccurrences("owner").single()
            assertEquals(job.scheduledAt, failure.scheduledAt)
            assertEquals(3, failure.attempts)
            assertEquals(if (expiredLease) "Worker lease expired." else "start unavailable", failure.error)
            assertTrue(service.listFailedOccurrences("stranger").isEmpty())

            // A fresh instance reads the durable record; a later success cannot erase it.
            val peer = PostgresJobService(db)
            db.execute("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?", job.id)
            assertTrue(peer.processNext { assertEquals(pending.scheduledAt, it.scheduledAt) })
            assertEquals(listOf(failure), peer.listFailedOccurrences("owner"))
        }
    }

    @Test
    fun `exhausted one off is failed and has a durable occurrence error`() = jobTest { db, service ->
        val job = service.create(continueAfterFailure = true)
        repeat(3) {
            db.execute("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?", job.id)
            service.processNext { error("start unavailable") }
        }
        assertEquals(JobStatus.FAILED, service.listJobs("owner").single().status)
        assertEquals("start unavailable", service.listFailedOccurrences("owner").single().error)
    }
}
