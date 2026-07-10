package ru.souz.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DesktopMemoryMaintenanceBackgroundRunner(
    private val controller: MemoryMaintenanceController,
    private val scope: CoroutineScope,
    private val isAppBusy: () -> Boolean = { false },
    private val initialDelay: Duration = 30.seconds,
    private val interval: Duration = 5.minutes,
) {
    private val logger = LoggerFactory.getLogger(DesktopMemoryMaintenanceBackgroundRunner::class.java)

    fun start(): Job = scope.launch {
        if (initialDelay > Duration.ZERO) {
            delay(initialDelay)
        }
        while (isActive) {
            runCatching { tick() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    logger.warn("Background memory maintenance tick failed: {}", error.message)
                }
            delay(if (interval > Duration.ZERO) interval else DEFAULT_INTERVAL)
        }
    }

    suspend fun tick(): MemoryMaintenanceStatus? {
        val status = controller.status()
        if (!status.canRunInBackground()) return null
        return controller.runDue()
    }

    private fun MemoryMaintenanceStatus.canRunInBackground(): Boolean {
        return preferences.mode != MemoryMaintenanceMode.OFF &&
            pendingClusters > 0 &&
            workerState != MemoryMaintenanceWorkerState.RUNNING &&
            !isAppBusy()
    }

    private companion object {
        val DEFAULT_INTERVAL: Duration = 5.minutes
    }
}
