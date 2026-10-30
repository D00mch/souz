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
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Owns children until the parent body exits, then cancels and joins them before returning. */
suspend fun <T> withSubagentTasks(block: suspend () -> T): T = coroutineScope {
    val tasks = SubagentTasks(this)
    withContext(tasks) {
        try {
            block()
        } finally {
            withContext(NonCancellable) { tasks.close() }
        }
    }
}

internal class SubagentTasks(private val scope: CoroutineScope) : AbstractCoroutineContextElement(Key) {
    private val mutex = Mutex()
    private val tasks = mutableMapOf<String, Deferred<Map<String, Any>>>()
    private var closed = false

    suspend fun spawn(execute: suspend () -> Map<String, Any>): String = mutex.withLock {
        currentCoroutineContext().ensureActive()
        check(!closed) { "Parent execution has ended." }
        val id = UUID.randomUUID().toString()
        // Register before starting; cancellation before dispatch still leaves a readable terminal job.
        val job = scope.async(start = CoroutineStart.LAZY) { execute() }
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
        if (timeoutMs > 0) withTimeoutOrNull(timeoutMs) { job.join() }
        val result = when {
            !job.isCompleted -> mapOf("status" to if (job.isCancelled) "cancelled" else "running")
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
        suspend fun current(): SubagentTasks = currentCoroutineContext()[Key]
            ?: throw SubagentInputException("subagent_unavailable", "Subagent tools require an active parent execution.")
    }
}
