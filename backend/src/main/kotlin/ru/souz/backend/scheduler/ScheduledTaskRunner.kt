package ru.souz.backend.scheduler

import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.backend.channels.tool.SEND_MESSAGE_TO_CHANNEL_TOOL_NAME
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.execution.service.AgentExecutionLauncher
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.jobs.JobRun

/** Client type of the hidden technical chat that each scheduled occurrence runs in. */
internal const val SCHEDULED_TASK_CHAT_TYPE = "scheduled_task"

data class ScheduledTaskConfig(
    val runsPerUserPerDay: Int = 100,
    val concurrentRuns: Int = 16,
    val runTimeoutMillis: Long = 300_000,
) {
    init {
        require(runsPerUserPerDay > 0 && concurrentRuns > 0 && runTimeoutMillis > 0)
    }
}

/** Starts bounded background executions without blocking the sequential job worker on completion. */
internal class ScheduledTaskRunner(
    private val chats: ChatRepository,
    private val executions: AgentExecutionService,
    private val launcher: AgentExecutionLauncher,
    private val scope: CoroutineScope,
    private val config: ScheduledTaskConfig,
    private val clock: Clock,
) {
    private val slots = Semaphore(config.concurrentRuns)

    @Suppress("SuspendFunSwallowedCancellation") // Every failure is rethrown after non-suspending permit cleanup.
    suspend fun run(job: JobRun) {
        slots.acquire()
        val execution = try {
            val now = clock.instant()
            val chat = chats.getOrCreate(
                Chat(
                    id = job.occurrenceId("chat"), userId = job.userId, title = "Scheduled task: ${job.title}",
                    archived = false, createdAt = now, updatedAt = now, clientType = SCHEDULED_TASK_CHAT_TYPE,
                )
            )
            executions.executeChatTurnIfAbsent(job.userId, chat.id, scheduledTaskInput(job), job.occurrenceId("execution"))
        } catch (error: Exception) {
            slots.release()
            throw error
        }
        if (execution == null) {
            slots.release()
            return
        }
        // Enter the finally block even if the application scope is already shutting down.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (withTimeoutOrNull(config.runTimeoutMillis) { launcher.join(execution.id) } == null) {
                    launcher.cancel(execution.id)
                    launcher.join(execution.id)
                }
            } finally {
                slots.release()
            }
        }
    }
}

/** Separate stable primary keys for this occurrence's chat and execution, independent of retries. */
internal fun JobRun.occurrenceId(entity: String): UUID =
    UUID.nameUUIDFromBytes("scheduled-task:$jobId:$scheduledAt:$entity".toByteArray(Charsets.UTF_8))

internal fun scheduledTaskInput(job: JobRun): String {
    val channelType = job.payload.path("replyChannelType").textValue()
    val channelId = job.payload.path("replyChannelId").textValue()
    val destination = if (channelType != null && channelId != null) {
        "Unless the task names another destination, use channelType=$channelType, channelId=$channelId."
    } else {
        "Use the destination named in the task."
    }
    return job.payload.path("prompt").asText() + "\n\n" + """
        Scheduled task "${job.title}", occurrence ${job.scheduledAt}.
        This chat is hidden; your final answer is not shown to the user.
        If a message is requested, use $SEND_MESSAGE_TO_CHANNEL_TOOL_NAME with channelType, channelId and text.
        $destination
        Send nothing when the task only asks for an action.
    """.trimIndent()
}
