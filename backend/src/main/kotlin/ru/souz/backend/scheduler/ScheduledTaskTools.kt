package ru.souz.backend.scheduler

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.tool.SEND_MESSAGE_TO_CHANNEL_TOOL_NAME
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobInfo
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobService
import ru.souz.jobs.JobStatus
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolSetup

internal class ToolCreateScheduledTask(
    private val jobs: JobService,
    private val channels: ChannelProviderRegistry,
    private val executions: AgentExecutionRepository,
    private val config: ScheduledTaskConfig,
    private val clock: Clock,
) : ToolSetup<ToolCreateScheduledTask.Input> {
    data class Input(
        @InputParamDescription(
            "Self-contained instruction the agent runs at each occurrence in a fresh chat without this " +
                "conversation's history. For a reminder, include the reminder text."
        )
        val prompt: String,
        @InputParamDescription(
            "True for reminders and any task that must send a message (default). Set false only for action-only tasks."
        )
        val requiresDelivery: Boolean = true,
        @InputParamDescription(
            "Whether a dispatch retry may launch this occurrence again after a failure left its outcome uncertain, " +
                "such as a lost worker lease. False (default, at least once): a retry launches a new run, so a run " +
                "may rarely repeat; prefer it when a missed run is worse, such as appointments, medication or " +
                "deadlines. True (at most once): a retry never launches it again, so a run may be missed; prefer it " +
                "when a repeat is worse, such as payments or messages to other people. Neither setting guarantees " +
                "delivery or restarts an agent interrupted by a server crash."
        )
        val atMostOnce: Boolean = false,
        @InputParamDescription("Short title shown in the task list. Defaults to the start of prompt.")
        val title: String? = null,
        @InputParamDescription("One-off run time, RFC 3339 with offset, e.g. 2026-10-13T09:00:00+03:00.")
        val runAt: String? = null,
        @InputParamDescription(
            "One-off run after this many seconds from now; any positive delay works, even a few seconds. " +
                "Prefer it for requests like 'in 10 minutes'."
        )
        val afterSeconds: Long? = null,
        @InputParamDescription("Recurring schedule: five-field Unix cron with minute precision, e.g. '0 9 * * 1-5'.")
        val cron: String? = null,
        @InputParamDescription("IANA time zone for cron, e.g. Europe/Moscow. Defaults to the user's time zone.")
        val timeZone: String? = null,
    )

    override val name: String = "scheduled-task.create"
    override val description: String =
        "Creates a one-off or recurring task that the agent runs at the scheduled time in a hidden chat. " +
            "Set exactly one of runAt, afterSeconds or cron. If cron cannot express a requested interval " +
            "(e.g. every 1000 seconds), use the nearest schedule it can and tell the user it was rounded. " +
            "Messages for the user go by default to the conversation where the task was created; " +
            "action-only tasks send nothing. The task exists only once this tool returns its taskId: on an " +
            "error, fix the arguments and call it again, and never tell the user a task is set without a taskId."

    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(
            request = "Напомни через 10 минут выпить воды",
            params = mapOf("title" to "Выпить воды", "prompt" to "Напомни пользователю выпить воды.", "afterSeconds" to 600),
        ),
        FewShotExample(
            request = "Каждый будний день в 9 утра присылай сводку погоды",
            params = mapOf("title" to "Погода по утрам", "prompt" to "Пришли пользователю сводку погоды на сегодня.", "cron" to "0 9 * * 1-5"),
        ),
    )

    override val returnParameters: ReturnParameters = taskReturnParameters

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val prompt = input.prompt.trim()
        if (prompt.isEmpty() || prompt.length > 16_384) throw BadInputException("prompt must have 1..16384 characters.")
        val title = input.title?.trim()?.takeIf { it.isNotEmpty() } ?: prompt.take(80)
        if (title.length > 200) throw BadInputException("title must have at most 200 characters.")
        if (input.requiresDelivery && !canDeliver(meta)) {
            throw BadInputException("Scheduled reminder requires enabled SendMessageToChannel; delivery is unavailable.")
        }
        val now = clock.instant()
        val schedule = schedule(input, meta, now)
        // The budget covers every active task, not just the first page.
        var planned = 0
        var cursor: UUID? = null
        do {
            val page = jobs.listJobs(meta.userId, limit = 100, beforeJobId = cursor, includeInactive = false)
            planned += page.items.sumOf { runsPerDay(it.schedule, now) }
            cursor = page.nextCursor
        } while (cursor != null)
        val needed = runsPerDay(schedule, now)
        if (planned + needed > config.runsPerUserPerDay) {
            throw BadInputException(
                "Daily run budget exceeded: this task needs $needed run(s) per day, " +
                    "${(config.runsPerUserPerDay - planned).coerceAtLeast(0)} of ${config.runsPerUserPerDay} remain. " +
                    "Cancel other tasks or choose a less frequent schedule."
            )
        }
        val payload = restJsonMapper.createObjectNode()
            .put("prompt", prompt)
            .put("requiresDelivery", input.requiresDelivery)
            .put("atMostOnce", input.atMostOnce)
        meta.conversationId
            ?.let { id -> channels.listAll(meta.userId).firstOrNull { it.channelId == id } }
            ?.let { payload.put("replyChannelType", it.channelType).put("replyChannelId", it.channelId) }
        // A recurring task records an exhausted occurrence and continues with the next one.
        val job = jobs.createJob(meta.userId, CreateJobRequest(title, payload, schedule, continueAfterFailure = true))
        return restJsonMapper.writeValueAsString(job.toView(meta.zone()))
    }

    /** Checks the creating execution's enabled-tool snapshot; dispatch checks the owner's settings again. */
    private suspend fun canDeliver(meta: ToolInvocationMeta): Boolean {
        val executionId = meta.requestId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return false
        val snapshot = executions.get(meta.userId, executionId)?.metadata?.get("enabledTools") ?: return false
        return restJsonMapper.readTree(snapshot).any { it.asText() == SEND_MESSAGE_TO_CHANNEL_TOOL_NAME }
    }

    private fun schedule(input: Input, meta: ToolInvocationMeta, now: Instant): JobSchedule = when {
        listOfNotNull(input.runAt, input.afterSeconds, input.cron).size != 1 ->
            throw BadInputException("Set exactly one of runAt, afterSeconds or cron.")
        input.runAt != null -> JobSchedule.Once(
            runCatching { OffsetDateTime.parse(input.runAt.trim()).toInstant() }.getOrNull()?.takeIf { it.isAfter(now) }
                ?: throw BadInputException("runAt must be a future RFC 3339 time with an offset.")
        )
        input.afterSeconds != null -> JobSchedule.Once(
            now.plusSeconds(input.afterSeconds.takeIf { it > 0 } ?: throw BadInputException("afterSeconds must be positive."))
        )
        else -> {
            val zone = (input.timeZone ?: meta.timeZone)?.trim()
                ?.takeIf { it in ZoneId.getAvailableZoneIds() }
                ?: throw BadInputException("cron needs an IANA timeZone, e.g. Europe/Moscow.")
            JobSchedule.Cron(input.cron!!.trim(), zone)
        }
    }
}

internal class ToolListScheduledTasks(
    private val jobs: JobService,
) : ToolSetup<ToolListScheduledTasks.Input> {
    data class Input(
        @InputParamDescription("Also show completed, failed and cancelled tasks. Defaults to false.")
        val includeInactive: Boolean? = null,
        @InputParamDescription("Page size from 1 to 100. Defaults to 20.")
        val limit: Int? = null,
        @InputParamDescription("nextCursor from the previous page; omit for the first page.")
        val cursor: String? = null,
    )

    override val name: String = "scheduled-task.list"
    override val description: String =
        "Lists the user's scheduled tasks, newest first, with their schedules, statuses and next run times. " +
            "When nextCursor is not null, pass it as cursor to get the next page."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(request = "Какие у меня запланированы задачи?", params = emptyMap()),
    )
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf(
            "tasks" to ReturnProperty("array", "Tasks, each shaped like scheduled-task.create's result."),
            "nextCursor" to ReturnProperty("string", "cursor for the next page; null on the last page."),
        ),
    )

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val zone = meta.zone()
        val limit = input.limit ?: 20
        if (limit !in 1..100) throw BadInputException("limit must be between 1 and 100.")
        val cursor = input.cursor?.let {
            runCatching { UUID.fromString(it) }.getOrNull() ?: throw BadInputException("cursor must be a nextCursor value.")
        }
        val page = jobs.listJobs(meta.userId, limit, cursor, includeInactive = input.includeInactive == true)
        val failures = jobs.listFailedOccurrences(meta.userId, page.items.mapTo(HashSet()) { it.id }).groupBy { it.jobId }
        val tasks = page.items.map { job ->
            job.toView(zone) + ("failedOccurrences" to failures[job.id].orEmpty().map {
                mapOf("scheduledAt" to it.scheduledAt.atZone(zone).toOffsetDateTime().toString(), "error" to it.error)
            })
        }
        return restJsonMapper.writeValueAsString(mapOf("tasks" to tasks, "nextCursor" to page.nextCursor?.toString()))
    }
}

internal class ToolDeleteScheduledTask(
    private val jobs: JobService,
) : ToolSetup<ToolDeleteScheduledTask.Input> {
    data class Input(
        @InputParamDescription("taskId from scheduled-task.create or scheduled-task.list.")
        val taskId: String,
    )

    override val name: String = "scheduled-task.delete"
    override val description: String =
        "Cancels a scheduled task so it never runs again. Its record stays in the task history, " +
            "and a run already in progress is not interrupted."
    override val fewShotExamples: List<FewShotExample> = listOf(
        FewShotExample(request = "Отмени напоминание про воду", params = mapOf("taskId" to "<taskId из scheduled-task.list>")),
    )
    override val returnParameters: ReturnParameters = taskReturnParameters

    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val id = runCatching { UUID.fromString(input.taskId.trim()) }.getOrNull()
            ?: throw BadInputException("taskId must be a task UUID.")
        val job = jobs.cancelJob(meta.userId, id) ?: throw BadInputException("Task not found.")
        return restJsonMapper.writeValueAsString(job.toView(meta.zone()))
    }
}

/** Planned runs per day: a one-off task counts once, a cron task by its weekly average, at least once. */
internal fun runsPerDay(schedule: JobSchedule, now: Instant): Int = when (schedule) {
    is JobSchedule.Once -> 1
    is JobSchedule.Cron -> {
        val time = ExecutionTime.forCron(cronParser.parse(schedule.expression).validate())
        val start = now.atZone(ZoneId.of(schedule.timeZone))
        val weekly = time.countExecutions(start, start.plusDays(7))
        maxOf(1, (weekly + 6) / 7)
    }
}

private val cronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

private val taskReturnParameters = ReturnParameters(
    properties = mapOf(
        "taskId" to ReturnProperty("string", "Task ID for scheduled-task.delete."),
        "title" to ReturnProperty("string"),
        "prompt" to ReturnProperty("string"),
        "requiresDelivery" to ReturnProperty("boolean", "Whether each occurrence must be able to send a message."),
        "atMostOnce" to ReturnProperty("boolean", "True: dispatch retries never launch an occurrence again; false: they may."),
        "schedule" to ReturnProperty("object", "runAt for a one-off task, or cron and timeZone."),
        "status" to ReturnProperty("string", "Dispatch status: pending, running, succeeded, failed or cancelled; not a delivery receipt."),
        "failedOccurrences" to ReturnProperty("array", "Latest occurrences that failed after all retries (list only)."),
        "nextRunAt" to ReturnProperty("string", "Next run in the user's time zone; absent once the task is finished."),
        "lastRunAt" to ReturnProperty("string"),
        "lastError" to ReturnProperty("string"),
    )
)

private fun JobInfo.isActive(): Boolean = status == JobStatus.PENDING || status == JobStatus.RUNNING

private fun ToolInvocationMeta.zone(): ZoneId =
    timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

private fun JobInfo.toView(zone: ZoneId): Map<String, Any?> = mapOf(
    "taskId" to id.toString(),
    "title" to title,
    "prompt" to payload.path("prompt").asText(),
    "requiresDelivery" to payload.path("requiresDelivery").asBoolean(true),
    "atMostOnce" to payload.path("atMostOnce").asBoolean(false),
    "schedule" to when (val schedule = schedule) {
        is JobSchedule.Once -> mapOf("runAt" to schedule.runAt?.atZone(zone)?.toOffsetDateTime()?.toString())
        is JobSchedule.Cron -> mapOf("cron" to schedule.expression, "timeZone" to schedule.timeZone)
    },
    "status" to status.name.lowercase(),
    "nextRunAt" to scheduledAt.takeIf { isActive() }?.atZone(zone)?.toOffsetDateTime()?.toString(),
    "lastRunAt" to lastFinishedAt?.atZone(zone)?.toOffsetDateTime()?.toString(),
    "lastError" to lastError,
)
