package ru.souz.jobs.impl

import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobListingTest {
    @Test
    fun `pages filter before limiting and use owner-scoped stable cursors`() = jobTest { db, service ->
        val active = List(5) { service.create() }
        repeat(24) { service.cancelJob("owner", service.create().id) }
        val foreign = service.create(userId = "other")
        // Equal timestamps exercise the UUID tie-breaker across pages.
        db.execute("update jobs set created_at = '2026-01-01T00:00:00Z'")
        assertEquals(20, service.listJobs("owner").items.size)
        val expected = active.map { it.id }.sortedByDescending { it.toString() }
        val first = service.listJobs("owner", limit = 2, includeInactive = false)
        assertEquals(expected.take(2), first.items.map { it.id })
        service.create() // A newer insertion must not shift an existing cursor.
        service.cancelJob("owner", assertNotNull(first.nextCursor)) // A terminal anchor remains usable.
        val second = service.listJobs("owner", 2, first.nextCursor, includeInactive = false)
        val last = service.listJobs("owner", 2, assertNotNull(second.nextCursor), includeInactive = false)
        assertEquals(expected.drop(2), (second.items + last.items).map { it.id })
        assertNull(last.nextCursor)
        assertTrue(service.listJobs("owner", beforeJobId = foreign.id).items.isEmpty())
        assertTrue(service.listJobs("owner", beforeJobId = UUID.randomUUID()).items.isEmpty())
        for (limit in listOf(0, 101)) assertFailsWith<IllegalArgumentException> { service.listJobs("owner", limit) }
    }

    @Test
    fun `failure reads return only the latest three per requested owned job`() = jobTest { db, service ->
        val jobs = List(3) { service.create() } + service.create(userId = "other")
        val epoch = Instant.parse("2026-01-01T00:00:00Z")
        jobs.forEach { job ->
            repeat(7) { index ->
                db.execute(
                    """
                    insert into job_occurrence_failures(job_id, scheduled_at, attempts, finished_at, error)
                    values (?, ?, 3, clock_timestamp(), ?)
                    """.trimIndent(), job.id, epoch.plusSeconds(index.toLong()).atOffset(ZoneOffset.UTC), "Failure $index",
                )
            }
        }
        val failures = service.listFailedOccurrences("owner", setOf(jobs[0].id, jobs[1].id, jobs[3].id))
        assertEquals(setOf(jobs[0].id, jobs[1].id), failures.map { it.jobId }.toSet())
        failures.groupBy { it.jobId }.values.forEach { recent ->
            assertEquals(listOf("Failure 6", "Failure 5", "Failure 4"), recent.map { it.error })
        }
        assertTrue(service.listFailedOccurrences("owner", emptySet()).isEmpty())
        assertFailsWith<IllegalArgumentException> { service.listFailedOccurrences("owner", List(101) { UUID.randomUUID() }.toSet()) }
    }
}
