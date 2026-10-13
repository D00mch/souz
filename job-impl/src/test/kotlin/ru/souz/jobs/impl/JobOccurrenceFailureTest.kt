package ru.souz.jobs.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobStatus

class JobOccurrenceFailureTest {
    @Test
    fun `cron advances after exhausted handler errors or leases and retains owner-scoped failures`() {
        for (expired in listOf(false, true)) jobTest { db, service ->
            val job = service.createJob("owner", CreateJobRequest("Recurring", jobTestMapper.createObjectNode(),
                JobSchedule.Cron("* * * * *", "UTC"), continueAfterFailure = true))
            repeat(3) { attempt ->
                db.execute("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?", job.id)
                val claim = assertNotNull(service.store.claim())
                assertEquals(attempt + 1, claim.job.attempts)
                assertEquals(job.scheduledAt, claim.job.scheduledAt)
                if (expired) db.execute("update jobs set lease_until = clock_timestamp() - interval '1 second' where id = ?", job.id)
                else assertTrue(service.store.finish(claim, "Start failed"))
            }
            service.store.claim()
            val current = service.listJobs("owner").single()
            assertEquals(JobStatus.PENDING, current.status)
            assertTrue(current.scheduledAt > job.scheduledAt)
            assertEquals(0, current.attempts)
            val failure = service.listFailedOccurrences("owner").single()
            assertEquals(job.scheduledAt, failure.scheduledAt)
            assertEquals(3, failure.attempts)
            assertEquals(if (expired) "Worker lease expired." else "Start failed", failure.error)
            assertTrue(service.listFailedOccurrences("stranger").isEmpty())
            db.execute("update jobs set available_at = clock_timestamp() - interval '1 second' where id = ?", job.id)
            val peer = PostgresJobService(db)
            peer.processNext { }
            assertEquals(listOf(failure), peer.listFailedOccurrences("owner"))
        }
    }
}
