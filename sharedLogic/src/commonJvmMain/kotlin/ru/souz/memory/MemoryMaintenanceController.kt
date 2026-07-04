package ru.souz.memory

import java.time.Instant

enum class MemoryMaintenanceWorkerState {
    IDLE,
    RUNNING,
    BLOCKED,
}

enum class MemoryMaintenanceBlockReason {
    DREAMER_DISABLED,
    LOCAL_MODEL_UNAVAILABLE,
    CLOUD_MODEL_UNAVAILABLE,
    DAILY_TOKEN_LIMIT,
    DAILY_CALL_LIMIT,
    PER_RUN_TOKEN_LIMIT,
    MAX_CALLS_PER_RUN,
    MAX_CLUSTERS_PER_RUN,
    NO_PENDING_CLUSTERS,
    NO_DETERMINISTIC_ACTIONS,
}

data class MemoryMaintenanceStatus(
    val preferences: MemoryMaintenancePreferences = MemoryMaintenancePreferences(),
    val workerState: MemoryMaintenanceWorkerState = MemoryMaintenanceWorkerState.IDLE,
    val pendingClusters: Int = 0,
    val blockedClusters: Int = 0,
    val cloudTokensUsedToday: Int = 0,
    val cloudCallsUsedToday: Int = 0,
    val usageIsEstimated: Boolean = false,
    val activeModelLabel: String? = null,
    val lastAttemptedAt: Instant? = null,
    val lastCompletedAt: Instant? = null,
    val nextBudgetResetAt: Instant? = null,
    val blockedReason: MemoryMaintenanceBlockReason? = null,
    val lastErrorCode: String? = null,
)

interface MemoryMaintenanceController {
    suspend fun status(): MemoryMaintenanceStatus

    suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus

    suspend fun runNow(): MemoryMaintenanceStatus
}

object NoopMemoryMaintenanceController : MemoryMaintenanceController {
    override suspend fun status(): MemoryMaintenanceStatus =
        MemoryMaintenanceStatus(blockedReason = MemoryMaintenanceBlockReason.DREAMER_DISABLED)

    override suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus =
        MemoryMaintenanceStatus(
            preferences = preferences.copy(mode = preferences.mode),
            blockedReason = if (preferences.mode == MemoryMaintenanceMode.OFF) {
                MemoryMaintenanceBlockReason.DREAMER_DISABLED
            } else {
                MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS
            },
        )

    override suspend fun runNow(): MemoryMaintenanceStatus =
        status()
}
