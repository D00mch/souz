package ru.souz.backend.channels

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger

/**
 * Keeps one long-lived coroutine running per currently-enabled binding id, so one binding's poll
 * cycle never blocks another's. Call [sync] periodically with the enabled bindings.
 */
internal class BindingLoopSupervisor<B>(
    private val scope: CoroutineScope,
    private val idOf: (B) -> UUID,
) {
    private val mutex = Mutex()
    private val jobs = mutableMapOf<UUID, Job>()

    suspend fun sync(bindings: List<B>, startLoop: suspend CoroutineScope.(B) -> Unit) {
        val byId = bindings.associateBy(idOf)
        mutex.withLock {
            val stale = jobs.keys - byId.keys
            stale.forEach { id -> jobs.remove(id)?.cancel() }
            byId.forEach { (id, binding) ->
                if (jobs[id]?.isActive != true) {
                    jobs[id] = scope.launch { startLoop(binding) }
                }
            }
        }
    }
}

/**
 * Repeats [poll] every [pollLoopDelayMs], logging (not propagating) failures. Don't wrap [poll]
 * itself in a semaphore — it starts with an idle long poll; gate only the real work inside it.
 */
internal suspend fun CoroutineScope.runBindingPollLoop(
    id: UUID,
    channelName: String,
    logger: Logger,
    pollLoopDelayMs: Long,
    poll: suspend () -> Unit,
) {
    while (isActive) {
        try {
            poll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("{} polling failed for binding {}: {}", channelName, id, e.message)
        }
        delay(pollLoopDelayMs)
    }
}
