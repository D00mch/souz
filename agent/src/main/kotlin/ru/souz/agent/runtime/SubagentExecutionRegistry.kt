package ru.souz.agent.runtime

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.souz.llms.ToolInvocationMeta

/** Live child jobs only. Hosts must authorize the owning execution before listing or cancelling. */
class SubagentExecutionRegistry {
    private data class Owner(val userId: String, val conversationId: String?, val executionId: String?)
    private data class Entry(val owner: Owner, val job: Job)

    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()

    suspend fun activeIds(meta: ToolInvocationMeta): List<String> = mutex.withLock {
        entries.filterValues { it.owner == meta.owner() && !it.job.isCompleted }.keys.toList()
    }

    /** True while cancellation is requested for a live child; false if absent or completion won. */
    suspend fun cancel(meta: ToolInvocationMeta, subagentId: String): Boolean = mutex.withLock {
        val entry = entries[subagentId]?.takeIf { it.owner == meta.owner() && !it.job.isCompleted }
            ?: return@withLock false
        entry.job.cancel(SubagentCancelledException(subagentId))
        entry.job.isCancelled
    }

    internal suspend fun <T> execute(meta: ToolInvocationMeta, block: suspend () -> T): T = coroutineScope {
        val id = UUID.randomUUID().toString()
        val child = async(start = CoroutineStart.LAZY) { block() }
        try {
            mutex.withLock { entries[id] = Entry(meta.owner(), child) }
            child.await()
        } finally {
            withContext(NonCancellable) {
                // Parent cancellation may resume await before the child's cleanup has finished.
                child.cancelAndJoin()
                mutex.withLock { entries.remove(id) }
            }
        }
    }

    private fun ToolInvocationMeta.owner() = Owner(userId, conversationId, requestId)
}

internal class SubagentCancelledException(val subagentId: String) :
    CancellationException("Subagent cancelled by user request. Tool side effects are not rolled back.")
