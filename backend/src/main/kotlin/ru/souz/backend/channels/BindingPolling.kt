package ru.souz.backend.channels

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.slf4j.Logger

/** Each binding owns its poll loop and captured state until it is disabled or removed. */
internal suspend fun pollBindings(
    delayMs: Long,
    logger: Logger,
    listPolls: suspend () -> Map<UUID, suspend () -> Unit>,
) = supervisorScope {
    val jobs = mutableMapOf<UUID, Job>()
    while (isActive) {
        try {
            val polls = listPolls()
            jobs.filterKeys { it !in polls }.values.forEach { it.cancel() }
            // Wait for cancelled workers to finish before reusing their binding IDs.
            jobs.entries.removeAll { it.value.isCompleted }
            for ((id, poll) in polls) {
                if (id in jobs) continue
                jobs[id] = launch {
                    while (isActive) {
                        try {
                            poll()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            logger.warn("Polling failed for binding {}", id)
                        }
                        delay(delayMs)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            logger.warn("Failed to refresh polling bindings.")
        }
        delay(delayMs)
    }
}
