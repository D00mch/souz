package ru.souz.backend.scheduler

import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.jobs.JobRun

internal const val SCHEDULED_TASK_CHAT_TYPE = "scheduled_task"
internal const val SCHEDULED_TASK_METADATA = "scheduledTask"

data class ScheduledTaskConfig(
    val runsPerUserPerDay: Int = 100,
    val concurrentRuns: Int = 16,
    val runTimeoutMillis: Long = 300_000,
) {
    init { require(runsPerUserPerDay > 0 && concurrentRuns > 0 && runTimeoutMillis > 0) }
}

/** Holds a job-worker lane until AgentExecutionService finishes its registered execution lifecycle. */
internal class ScheduledTaskRunner(
    private val chats: ChatService,
    private val executions: AgentExecutionService,
) {
    @Suppress("SuspendFunSwallowedCancellation") // Preserve and rethrow cancellation after non-cancellable rejection cleanup.
    suspend fun run(job: JobRun) {
        val id = job.executionId()
        val chat = chats.createClient(job.userId, "scheduled-task:$id", SCHEDULED_TASK_CHAT_TYPE, job.title).chat
        try {
            executions.executeScheduledTask(
                job.userId, chat.id, id, scheduledTaskInput(job), job.payload.path("requiresDelivery").asBoolean(true),
            )
        } catch (error: Exception) {
            try {
                withContext(NonCancellable) { chats.deleteEmptyScheduledChat(job.userId, chat.id) }
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }
}

internal fun JobRun.executionId(): UUID {
    val attemptKey = if (payload.path("atMostOnce").asBoolean(false)) "" else ":$attempt"
    return UUID.nameUUIDFromBytes("scheduled-task:$jobId:$scheduledAt$attemptKey".toByteArray(Charsets.UTF_8))
}

internal fun scheduledTaskInput(job: JobRun): String = buildString {
    appendLine(job.payload.path("prompt").asText())
    appendLine()
    appendLine("Scheduled task '${job.title}', occurrence ${job.scheduledAt}. This is a hidden chat; your final answer is not delivered to the user. Do not ask interactive questions.")
    append("If the task requires a message, use SendMessageToChannel with channelType, channelId and text. ")
    val type = job.payload.path("replyChannelType").textValue()
    val id = job.payload.path("replyChannelId").textValue()
    if (type != null && id != null) append("Unless the task names another destination, use channelType=$type, channelId=$id. ")
    append("Send nothing for action-only tasks.")
}
