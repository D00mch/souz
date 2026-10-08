package ru.souz.jobs.impl

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobStatus

class PostgresJobServiceTest {
    @Test
    fun `creation persists title JSON and schedule while listing and cancellation respect ownership`() = jobTest { db, service ->
        val payload = jobTestMapper.readTree("{\"nested\":{\"value\":42},\"items\":[true,null]}") as ObjectNode
        val scheduledAt = Instant.parse("2035-01-01T09:00:00Z")
        val job = service.createJob("alice", CreateJobRequest("Report", payload, JobSchedule.Once(scheduledAt)))
        payload.put("addedAfterCreation", true)
        service.create(userId = "bob")

        val restored = PostgresJobService(db).listJobs("alice").single()
        assertEquals(job.id, restored.id)
        assertEquals("Report", restored.title)
        assertEquals(42, restored.payload["nested"]["value"].asInt())
        assertFalse(restored.payload.has("addedAfterCreation"))
        assertEquals(JobSchedule.Once(scheduledAt), restored.schedule)
        assertEquals(scheduledAt, restored.scheduledAt)
        assertEquals(scheduledAt, restored.availableAt)
        assertEquals(JobStatus.PENDING, restored.status)
        assertEquals(0, restored.attempts)
        assertNull(service.cancelJob("bob", job.id))
        assertNull(service.cancelJob("alice", UUID.randomUUID()))
        assertEquals(JobStatus.CANCELLED, service.cancelJob("alice", job.id)?.status)
        assertEquals(service.listJobs("alice").single(), service.cancelJob("alice", job.id))
        assertEquals("bob", service.store.claim()?.job?.userId)
    }

    @Test
    fun `creation rejects blank titles nonobject JSON and invalid cron or timezone`() = jobTest { _, service ->
        assertFailsWith<IllegalArgumentException> { service.create(title = "  ") }
        for (json in listOf("[]", "null", "42", "\"text\"")) {
            assertFailsWith<IllegalArgumentException> {
                service.createJob("owner", CreateJobRequest("Invalid", jobTestMapper.readTree(json)))
            }
        }
        for (schedule in listOf(
            JobSchedule.Cron("0 0 9 * * *", "UTC"),
            JobSchedule.Cron("61 * * * *", "UTC"),
            JobSchedule.Cron("0 9 * * *", "Not/A_TimeZone"),
        )) {
            assertFailsWith<IllegalArgumentException> { service.create(schedule = schedule) }
        }
        assertTrue(service.listJobs("owner").isEmpty())
    }

    @Test
    fun `recurring occurrence coalesces missed times and advances only after success`() = jobTest { db, service ->
        val schedule = JobSchedule.Cron("* * * * *", "UTC")
        val job = service.create(schedule = schedule)
        db.execute("update jobs set scheduled_at = date_trunc('minute', clock_timestamp()) - interval '10 minutes', available_at = clock_timestamp() - interval '10 minutes' where id = ?", job.id)
        val missed = service.listJobs("owner").single().scheduledAt
        var runs = 0

        assertTrue(service.processNext { run ->
            runs++
            assertEquals(job.id, run.jobId)
            assertEquals(missed, run.scheduledAt)
            assertEquals(1, run.attempt)
        })

        val next = service.listJobs("owner").single()
        assertEquals(schedule, next.schedule)
        assertEquals(JobStatus.PENDING, next.status)
        assertEquals(0, next.attempts)
        assertTrue(next.scheduledAt > db.databaseNow())
        assertEquals(next.scheduledAt, next.availableAt)
        assertNotNull(next.lastFinishedAt)
        assertFalse(service.processNext { runs++ })
        assertEquals(1, runs)
    }

    @Test
    fun `succeeded job cancellation is idempotent and never requeues it`() = jobTest { _, service ->
        val job = service.create()
        assertTrue(service.processNext {})
        val succeeded = service.listJobs("owner").single()
        assertEquals(JobStatus.SUCCEEDED, succeeded.status)
        assertEquals(succeeded, service.cancelJob("owner", job.id))
        assertFalse(service.processNext { error("Completed jobs cannot run again") })
    }
}
