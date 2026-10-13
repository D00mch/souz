package ru.souz.backend.scheduler

import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.tool.SEND_MESSAGE_TO_CHANNEL_TOOL_NAME
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.JobInfo
import ru.souz.jobs.JobSchedule
import ru.souz.jobs.JobService
import ru.souz.jobs.JobStatus
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.giga.toGiga
import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolSetup

internal class ScheduledTaskTools(
    jobs: JobService,
    channels: ChannelProviderRegistry,
    executions: AgentExecutionRepository,
    clock: Clock,
) : AgentToolCatalog {
    override val toolsByCategory = mapOf(ToolCategory.SCHEDULING to listOf(
        CreateScheduledTask(jobs, channels, executions, clock).toGiga(),
        ListScheduledTasks(jobs).toGiga(),
        DeleteScheduledTask(jobs).toGiga(),
    ).associateBy { it.fn.name })
}

private class CreateScheduledTask(
    private val jobs: JobService,
    private val channels: ChannelProviderRegistry,
    private val executions: AgentExecutionRepository,
    private val clock: Clock,
) : ToolSetup<CreateScheduledTask.Input> {
    data class Input(
        @InputParamDescription("Self-contained agent instruction; each run starts without this conversation's history.")
        val prompt: String,
        @InputParamDescription("Short task title; defaults to the beginning of prompt.")
        val title: String? = null,
        @InputParamDescription("Future one-off RFC 3339 timestamp with offset.")
        val runAt: String? = null,
        @InputParamDescription("Positive delay in seconds for a one-off task, including delays under a minute.")
        val afterSeconds: Long? = null,
        @InputParamDescription("Five-field Unix cron, e.g. 0 9 * * 1-5.")
        val cron: String? = null,
        @InputParamDescription("IANA cron time zone; defaults to the user's time zone.")
        val timeZone: String? = null,
        @InputParamDescription("True for reminders/messages. False for action-only tasks that need no message delivery.")
        val requiresDelivery: Boolean = true,
        @InputParamDescription("Choose true when duplicate effects are worse than a missed run: never relaunch an admitted occurrence. False retries uncertain outcomes and may duplicate effects. Neither mode guarantees delivery after a process crash.")
        val atMostOnce: Boolean = false,
    )

    override val name = "scheduled-task.create"
    override val description = "Schedule an agent task with exactly one of runAt, afterSeconds or cron. " +
        "Messages default to this conversation; action-only tasks stay silent. A daily execution quota applies. " +
        "Only confirm creation after this tool returns taskId."
    override val fewShotExamples = listOf(FewShotExample(
        request = "Напомни через 10 минут выпить воды",
        params = mapOf("prompt" to "Напомни пользователю выпить воды.", "afterSeconds" to 600),
    ))
    override val returnParameters = taskReturnParameters
    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val prompt = input.prompt.trim()
        if (prompt.isEmpty() || prompt.length > 16_384) throw BadInputException("prompt must have 1..16384 characters.")
        val title = input.title?.trim()?.takeIf(String::isNotEmpty) ?: prompt.take(80)
        if (title.length > 200) throw BadInputException("title must have at most 200 characters.")
        if (input.requiresDelivery) {
            val executionId = meta.requestId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val enabled = executionId?.let { executions.get(meta.userId, it)?.metadata?.get("enabledTools") }
            if (enabled == null || restJsonMapper.readTree(enabled).none { it.asText() == SEND_MESSAGE_TO_CHANNEL_TOOL_NAME }) {
                throw BadInputException("Scheduled messages require enabled $SEND_MESSAGE_TO_CHANNEL_TOOL_NAME.")
            }
        }
        val schedule = schedule(input, meta, clock.instant())
        val payload = restJsonMapper.createObjectNode().put("prompt", prompt)
            .put("atMostOnce", input.atMostOnce).put("requiresDelivery", input.requiresDelivery)
        channels.listAll(meta.userId).firstOrNull { it.channelId == meta.conversationId }?.let {
            payload.put("replyChannelType", it.channelType).put("replyChannelId", it.channelId)
        }
        val job = jobs.createJob(meta.userId, CreateJobRequest(title, payload, schedule, continueAfterFailure = true))
        return restJsonMapper.writeValueAsString(job.view(meta.zone()))
    }

    private fun schedule(input: Input, meta: ToolInvocationMeta, now: Instant): JobSchedule {
        if (listOfNotNull(input.runAt, input.afterSeconds, input.cron).size != 1) {
            throw BadInputException("Set exactly one of runAt, afterSeconds or cron.")
        }
        if (input.cron != null) return JobSchedule.Cron(input.cron.trim(), input.timeZone ?: meta.timeZone ?: "UTC")
        val runAt = runCatching {
            input.runAt?.let { OffsetDateTime.parse(it.trim()).toInstant() } ?: now.plusSeconds(input.afterSeconds!!)
        }.getOrNull()
        if (runAt == null || !runAt.isAfter(now)) throw BadInputException("Use a future RFC 3339 runAt or positive afterSeconds.")
        return JobSchedule.Once(runAt)
    }
}

private class ListScheduledTasks(private val jobs: JobService) : ToolSetup<ListScheduledTasks.Input> {
    data class Input(
        @InputParamDescription("Include completed, failed and cancelled tasks.")
        val includeInactive: Boolean = false,
        @InputParamDescription("Page size, from 1 to 100; defaults to 20.")
        val limit: Int = 20,
        @InputParamDescription("nextCursor from the preceding page; omit for the first page.")
        val cursor: String? = null,
    )
    override val name = "scheduled-task.list"
    override val description = "List your tasks, schedules, execution states and recent failed occurrences."
    override val fewShotExamples = listOf(FewShotExample("Какие у меня запланированы задачи?", emptyMap()))
    override val returnParameters = ReturnParameters(properties = mapOf(
        "tasks" to ReturnProperty("array"), "nextCursor" to ReturnProperty("string", "Use as cursor for the next page; null when complete."),
    ))
    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val zone = meta.zone()
        if (input.limit !in 1..100) throw BadInputException("limit must be between 1 and 100.")
        val cursor = input.cursor?.let { value ->
            runCatching { UUID.fromString(value) }.getOrNull() ?: throw BadInputException("cursor must be a UUID.")
        }
        val page = jobs.listJobs(meta.userId, input.limit, cursor, input.includeInactive)
        val failures = jobs.listFailedOccurrences(meta.userId, page.items.map { it.id }.toSet()).groupBy { it.jobId }
        val tasks = page.items.map { job ->
            job.view(zone) + ("failedOccurrences" to failures[job.id].orEmpty().map {
                mapOf("scheduledAt" to it.scheduledAt.atZone(zone).toString(), "error" to it.error)
            })
        }
        return restJsonMapper.writeValueAsString(mapOf("tasks" to tasks, "nextCursor" to page.nextCursor))
    }
}

private class DeleteScheduledTask(private val jobs: JobService) : ToolSetup<DeleteScheduledTask.Input> {
    data class Input(@InputParamDescription("taskId returned by create or list.") val taskId: String)
    override val name = "scheduled-task.delete"
    override val description = "Cancel future runs of your task. Its history remains; an already-started execution may finish."
    override val fewShotExamples = listOf(FewShotExample("Отмени задачу", mapOf("taskId" to "<taskId>")))
    override val returnParameters = taskReturnParameters
    override fun invoke(input: Input, meta: ToolInvocationMeta): String = runBlocking { suspendInvoke(input, meta) }

    override suspend fun suspendInvoke(input: Input, meta: ToolInvocationMeta): String {
        val id = runCatching { UUID.fromString(input.taskId.trim()) }.getOrNull()
            ?: throw BadInputException("taskId must be a UUID.")
        val job = jobs.cancelJob(meta.userId, id) ?: throw BadInputException("Task not found.")
        return restJsonMapper.writeValueAsString(job.view(meta.zone()))
    }
}

private val taskReturnParameters = ReturnParameters(properties = mapOf(
    "taskId" to ReturnProperty("string"), "title" to ReturnProperty("string"), "prompt" to ReturnProperty("string"),
    "schedule" to ReturnProperty("object", "runAt, or cron and timeZone."),
    "atMostOnce" to ReturnProperty("boolean"), "requiresDelivery" to ReturnProperty("boolean"),
    "status" to ReturnProperty("string", "pending, running, succeeded, failed or cancelled; not a delivery receipt."),
    "nextRunAt" to ReturnProperty("string"), "lastRunAt" to ReturnProperty("string"), "lastError" to ReturnProperty("string"),
))

private fun JobInfo.active() = status == JobStatus.PENDING || status == JobStatus.RUNNING
private fun ToolInvocationMeta.zone(): ZoneId = timeZone?.let(ZoneId::of) ?: ZoneOffset.UTC
private fun JobInfo.view(zone: ZoneId): Map<String, Any?> = mapOf(
    "taskId" to id, "title" to title, "prompt" to payload.path("prompt").asText(),
    "requiresDelivery" to payload.path("requiresDelivery").asBoolean(true),
    "atMostOnce" to payload.path("atMostOnce").asBoolean(false),
    "schedule" to when (val schedule = schedule) {
        is JobSchedule.Once -> mapOf("runAt" to schedule.runAt?.atZone(zone)?.toOffsetDateTime()?.toString())
        is JobSchedule.Cron -> mapOf("cron" to schedule.expression, "timeZone" to schedule.timeZone)
    },
    "status" to status.name.lowercase(),
    "nextRunAt" to scheduledAt.takeIf { active() }?.atZone(zone)?.toOffsetDateTime()?.toString(),
    "lastRunAt" to lastFinishedAt?.atZone(zone)?.toOffsetDateTime()?.toString(), "lastError" to lastError,
)
