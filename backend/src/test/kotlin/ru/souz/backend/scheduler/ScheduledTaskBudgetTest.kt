package ru.souz.backend.scheduler

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.jobs.JobSchedule

class ScheduledTaskBudgetTest {
    @Test
    fun `one-off tasks cost one run and cron tasks their weekly average, at least one`() {
        val monday = Instant.parse("2026-10-12T12:00:00Z")
        mapOf(
            JobSchedule.Once(monday.plusSeconds(60)) to 1,
            JobSchedule.Cron("0 9 * * 1", "UTC") to 1,
            JobSchedule.Cron("0 9 1 1 *", "UTC") to 1,
            JobSchedule.Cron("0 9-17 * * 1-5", "UTC") to 7,
        ).forEach { (schedule, expected) ->
            assertEquals(expected, runsPerDay(schedule, monday), schedule.toString())
        }
    }
}
