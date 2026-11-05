package ru.souz.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.agent.SubagentInputException
import ru.souz.llms.restJsonMapper
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

val DEFAULT_SUBAGENT_TASK_TIMEOUT: Duration = 10.minutes

/** Owns children until the parent body exits, then cancels and joins them before returning. */
suspend fun <T> withSubagentTasks(
    taskTimeout: Duration = DEFAULT_SUBAGENT_TASK_TIMEOUT,
    block: suspend () -> T,
): T = coroutineScope {
    val tasks = SubagentTasks(this, taskTimeout)
    withContext(tasks) {
        try {
            block()
        } finally {
            withContext(NonCancellable) { tasks.close() }
        }
    }
}

internal class SubagentTasks(
    private val scope: CoroutineScope,
    private val taskTimeout: Duration,
) : AbstractCoroutineContextElement(Key) {
    init {
        require(taskTimeout.isPositive() && taskTimeout.isFinite()) { "Subagent task timeout must be positive and finite." }
    }

    private val mutex = Mutex()
    private val tasks = mutableMapOf<String, Deferred<Map<String, Any>>>()
    private var closed = false

    suspend fun spawn(execute: suspend () -> Map<String, Any>): String = mutex.withLock {
        currentCoroutineContext().ensureActive()
        check(!closed) { "Parent execution has ended." }
        if (tasks.size >= MAX_TASKS) {
            throw SubagentInputException("subagent_task_limit", "At most $MAX_TASKS children may be started in one execution.")
        }
        if (tasks.values.count { !it.isCompleted } >= MAX_ACTIVE_TASKS) {
            throw SubagentInputException("subagent_concurrency_limit", "At most $MAX_ACTIVE_TASKS children may be active; wait for one to finish.")
        }
        val id = UUID.randomUUID().toString()
        // Register before starting; cancellation before dispatch still leaves a readable terminal job.
        val job = scope.async(start = CoroutineStart.LAZY) {
            val result = withTimeoutOrNull(taskTimeout) { execute() } ?: mapOf(
                "status" to "failed",
                "error" to mapOf("code" to "subagent_timeout", "message" to "Child exceeded its $taskTimeout execution deadline."),
                "sideEffectsMayHaveOccurred" to true,
            )
            val bytes = restJsonMapper.writeValueAsBytes(result).size
            if (bytes <= MAX_RESULT_BYTES) result else mapOf(
                "status" to "failed",
                "error" to mapOf("code" to "subagent_result_limit", "message" to "Child result exceeded the 64 KiB retention limit."),
                "resultBytes" to bytes,
                "sideEffectsMayHaveOccurred" to true,
            )
        }
        tasks[id] = job
        job.start()
        id
    }

    suspend fun status(id: String, timeoutMs: Long = 0, cancel: Boolean = false): Map<String, Any> {
        val job = mutex.withLock {
            val job = tasks[id] ?: throw SubagentInputException("subagent_not_found", "No child with this ID in the current execution.")
            if (cancel) job.cancel(CancellationException("Cancelled by parent tool"))
            job
        }
        if (timeoutMs > 0) withTimeoutOrNull(timeoutMs.milliseconds) { job.join() }
        val result = when {
            !job.isCompleted -> mapOf("status" to if (job.isCancelled) "cancelling" else "running")
            job.isCancelled -> mapOf("status" to "cancelled")
            else -> job.await()
        }
        currentCoroutineContext().ensureActive()
        return mapOf("subagentId" to id) + result
    }

    suspend fun close() = mutex.withLock {
        closed = true
        tasks.values.forEach { it.cancel() }
        tasks.clear()
        // The enclosing coroutineScope joins every child, including cancellation cleanup.
    }

    companion object Key : CoroutineContext.Key<SubagentTasks> {
        const val MAX_ACTIVE_TASKS = 8
        const val MAX_TASKS = 32
        // Together with MAX_TASKS, bounds retained result JSON to 2 MiB per execution.
        private const val MAX_RESULT_BYTES = 64 * 1024

        suspend fun current(): SubagentTasks = currentCoroutineContext()[Key]
            ?: throw SubagentInputException("subagent_unavailable", "Subagent tools require an active parent execution.")
    }
}
