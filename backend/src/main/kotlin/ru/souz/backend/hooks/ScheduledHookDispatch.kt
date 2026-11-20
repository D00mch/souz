package ru.souz.backend.hooks

import io.micrometer.core.instrument.Gauge
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.backend.channels.tool.SEND_MESSAGE_TO_CHANNEL_TOOL_NAME
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.execution.service.AgentExecutionLauncher
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.backend.scheduler.ScheduledTaskConfig

internal const val SCHEDULED_TASK_CHAT_TYPE = "scheduled_task"

/** Starts scheduled executions and holds capacity until their runtime has stopped. */
internal class ScheduledHookDispatch(
    private val chats: ChatRepository,
    private val executions: AgentExecutionService,
    private val launcher: AgentExecutionLauncher,
    private val scope: CoroutineScope,
    private val config: ScheduledTaskConfig,
    private val clock: Clock,
    metrics: BackendMetrics,
) {
    private val running = MutableStateFlow<Map<UUID, String>>(emptyMap())
    private val dispatchDelay = metrics.registry.timer("souz.scheduled.task.dispatch.delay")

    init {
        Gauge.builder("souz.scheduled.task.slots", running) { it.value.size.toDouble() }
            .tag("state", "busy").register(metrics.registry)
        Gauge.builder("souz.scheduled.task.slots", running) { (config.concurrentRuns - it.value.size).toDouble() }
            .tag("state", "free").register(metrics.registry)
    }

    /** The sequential worker waits before claiming and skips owners with an active turn. */
    suspend fun awaitCapacity(): Set<String> = running.first { it.size < config.concurrentRuns }.values.toSet()

    @Suppress("SuspendFunSwallowedCancellation") // Dispatch errors are rethrown after non-cancellable cleanup.
    suspend fun run(
        userId: String,
        title: String,
        content: String,
        occurrenceKey: String,
        scheduledAt: Instant,
        requiresDelivery: Boolean,
    ) {
        val now = clock.instant()
        val chat = chats.getOrCreate(
            Chat(
                id = scheduledOccurrenceId(occurrenceKey, "chat"),
                userId = userId,
                title = "Scheduled task: $title",
                archived = false,
                createdAt = now,
                updatedAt = now,
                clientType = SCHEDULED_TASK_CHAT_TYPE,
            )
        )
        val started = try {
            executions.executeScheduledTurnIfAbsent(
                userId = userId,
                chatId = chat.id,
                content = content,
                executionId = scheduledOccurrenceId(occurrenceKey, "execution"),
                occurrenceKey = occurrenceKey,
                dailyLaunchLimit = config.runsPerUserPerDay,
                requiredTools = if (requiresDelivery) setOf(SEND_MESSAGE_TO_CHANNEL_TOOL_NAME) else emptySet(),
            )
        } catch (error: Exception) {
            // A rejected dispatch leaves no execution, so its hidden chat would stay empty forever.
            try {
                withContext(NonCancellable) { chats.deleteEmptyScheduledChat(userId, chat.id) }
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        } ?: return
        running.update { it + (started.id to userId) }
        dispatchDelay.record(maxOf(Duration.ZERO, Duration.between(scheduledAt, clock.instant())))
        // Undispatched start enters the finally block even if the application scope is shutting down.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withTimeoutOrNull(config.runTimeoutMillis) { launcher.join(started.id) }
            } finally {
                withContext(NonCancellable) {
                    launcher.cancel(started.id)
                    launcher.join(started.id)
                    running.update { it - started.id }
                }
            }
        }
    }
}

private fun scheduledOccurrenceId(occurrenceKey: String, entity: String): UUID =
    UUID.nameUUIDFromBytes("scheduled-task:$occurrenceKey:$entity".toByteArray(Charsets.UTF_8))
