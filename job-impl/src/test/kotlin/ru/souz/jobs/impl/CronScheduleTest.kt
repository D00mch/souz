package ru.souz.jobs.impl

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CronScheduleTest {
    @Test
    fun `unix cron follows weekdays local time and is strictly after the supplied instant`() {
        assertEquals(
            Instant.parse("2026-10-12T06:00:00Z"),
            nextCronTime("0 9 * * 1-5", "Europe/Moscow", Instant.parse("2026-10-09T06:00:00Z")),
        )
    }

    @Test
    fun `cron skips nonexistent spring time and observes the autumn UTC offset`() {
        assertEquals(
            Instant.parse("2026-03-30T00:30:00Z"),
            nextCronTime("30 2 * * *", "Europe/Berlin", Instant.parse("2026-03-28T02:00:00Z")),
        )
        assertEquals(
            Instant.parse("2026-10-25T00:30:00Z"),
            nextCronTime("30 2 * * *", "Europe/Berlin", Instant.parse("2026-10-24T02:00:00Z")),
        )
    }
}
