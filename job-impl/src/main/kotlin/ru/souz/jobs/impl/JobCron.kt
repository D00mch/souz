package ru.souz.jobs.impl

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Instant
import java.time.ZoneId

internal fun nextCronTime(expression: String, timeZone: String, after: Instant): Instant {
    require(timeZone in ZoneId.getAvailableZoneIds()) { "An IANA time zone is required." }
    val cron = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX)).parse(expression)
    cron.validate()
    return ExecutionTime.forCron(cron).nextExecution(after.atZone(ZoneId.of(timeZone)))
        .orElseThrow { IllegalArgumentException("Cron schedule has no future occurrence.") }.toInstant()
}
