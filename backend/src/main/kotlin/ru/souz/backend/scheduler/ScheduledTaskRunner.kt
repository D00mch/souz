package ru.souz.backend.scheduler

import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.backend.channels.tool.SEND_MESSAGE_TO_CHANNEL_TOOL_NAME
import ru.souz.backend.channels.tool.ToolSendMessageToChannel
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.execution.model.AgentExecution
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

/**
 * Job handler for scheduled tasks. It starts the agent in a hidden chat and returns, so the
 * sequential job worker keeps claiming due tasks while up to [ScheduledTaskConfig.concurrentRuns]
 * executions run. When every slot is busy, the handler waits and the next tasks stay in the queue.
 * A start failure removes the occurrence's empty hidden chat and is rethrown for the job service's retries.
 */
internal class ScheduledTaskRunner(
    private val chats: ChatRepository,
    private val executions: AgentExecutionService,
    private val launcher: AgentExecutionLauncher,
    private val scope: CoroutineScope,
    private val config: ScheduledTaskConfig,
    private val clock: Clock,
) {
    private val slots = Semaphore(config.concurrentRuns)

    @Suppress("SuspendFunSwallowedCancellation") // Dispatch errors, cancellation included, are rethrown after non-cancellable cleanup.
    suspend fun run(job: JobRun) {
        slots.acquire()
        var execution: AgentExecution? = null
        try {
            val now = clock.instant()
            val chat = chats.getOrCreate(
                Chat(
                    id = job.occurrenceId("chat"), userId = job.userId, title = "Scheduled task: ${job.title}",
                    archived = false, createdAt = now, updatedAt = now, clientType = SCHEDULED_TASK_CHAT_TYPE,
                )
            )
            execution = try {
                executions.executeChatTurnIfAbsent(
                    job.userId, chat.id, scheduledTaskInput(job), job.occurrenceId("execution"), config.runsPerUserPerDay,
                    requiredTools = if (job.payload.path("requiresDelivery").asBoolean(true)) {
                        setOf(SEND_MESSAGE_TO_CHANNEL_TOOL_NAME)
                    } else emptySet(),
                )
            } catch (error: Exception) {
                // A rejected dispatch leaves no execution, so its hidden chat would stay empty forever.
                try {
                    withContext(NonCancellable) { chats.deleteEmptyScheduledChat(job.userId, chat.id) }
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
        } finally {
            if (execution == null) slots.release()
        }
        val started = execution ?: return
        // Undispatched start enters the finally block even if the application scope is shutting down.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (withTimeoutOrNull(config.runTimeoutMillis) { launcher.join(started.id) } == null) {
                    launcher.cancel(started.id)
                    launcher.join(started.id)
                }
            } finally {
                slots.release()
            }
        }
    }
}

/**
 * Stable chat and execution IDs for this occurrence. At most once keys them by occurrence, so a
 * retry or a competing worker finds the existing execution and never launches it again. At least
 * once also keys them by attempt, so a retry after an uncertain outcome launches a new execution.
 */
internal fun JobRun.occurrenceId(entity: String): UUID {
    val attemptKey = if (payload.path("atMostOnce").asBoolean(false)) "" else ":attempt-$attempt"
    return UUID.nameUUIDFromBytes("scheduled-task:$jobId:$scheduledAt$attemptKey:$entity".toByteArray(Charsets.UTF_8))
}

internal fun scheduledTaskInput(job: JobRun): String {
    val channelType = job.payload.path("replyChannelType").textValue()
    val channelId = job.payload.path("replyChannelId").textValue()
    // Naming every argument lets the model call the tool without guessing; the names follow the tool.
    val typeArgument = ToolSendMessageToChannel.Input::channelType.name
    val idArgument = ToolSendMessageToChannel.Input::channelId.name
    val textArgument = ToolSendMessageToChannel.Input::text.name
    val destination = if (channelType != null && channelId != null) {
        "If the task calls for a message to the user and names no other destination, send it to the " +
            "conversation where the task was created: $SEND_MESSAGE_TO_CHANNEL_TOOL_NAME with " +
            "$typeArgument=$channelType, $idArgument=$channelId and the message in $textArgument."
    } else {
        "If the task calls for a message to the user, send it with $SEND_MESSAGE_TO_CHANNEL_TOOL_NAME to the " +
            "destination the task names, passing $typeArgument, $idArgument and the message in $textArgument."
    }
    return listOf(
        job.payload.path("prompt").asText(),
        "",
        "This is scheduled task \"${job.title}\", occurrence ${job.scheduledAt}. It runs in a hidden " +
            "technical chat: your final answer is not shown to the user.",
        destination,
        "Send nothing when the task only asks for an action.",
    ).joinToString("\n")
}
