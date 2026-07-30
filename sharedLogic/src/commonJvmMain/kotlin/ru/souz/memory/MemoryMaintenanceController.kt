package ru.souz.memory

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import ru.souz.llms.LLMModel

const val MEMORY_MAINTENANCE_REASON_DREAMER_REGION_REWRITE = "dreamer_region_rewrite"
const val MEMORY_MAINTENANCE_REASON_LEGACY_CHAT_MIGRATION = "legacy_chat_fact_migration"

data class DreamerMaintenanceTarget(
    val ownerId: MemoryOwnerId,
    val scope: MemoryScope,
    val anchorFactId: String? = null,
)

fun dreamerRegionMaintenanceClusterKey(
    ownerId: MemoryOwnerId,
    scope: MemoryScope,
    anchorFactId: String? = null,
): String {
    val normalized = scope.normalized()
    return listOf(
        DREAMER_REGION_CLUSTER_PREFIX,
        ownerId.value.toBase64Url(),
        normalized.type.toBase64Url(),
        normalized.id.toBase64Url(),
    ).let { parts ->
        anchorFactId?.let { parts + it.toBase64Url() } ?: parts
    }.joinToString(":")
}

fun parseDreamerRegionMaintenanceClusterKey(clusterKey: String): DreamerMaintenanceTarget? {
    val parts = clusterKey.split(':')
    if (parts.size !in 4..5 || parts.first() != DREAMER_REGION_CLUSTER_PREFIX) return null
    val owner = parts[1].fromBase64Url() ?: return null
    val type = parts[2].fromBase64Url() ?: return null
    val id = parts[3].fromBase64Url() ?: return null
    val anchorFactId = parts.getOrNull(4)?.fromBase64Url() ?: if (parts.size == 5) return null else null
    return DreamerMaintenanceTarget(
        ownerId = MemoryOwnerId(owner),
        scope = MemoryScope(type, id).normalized(),
        anchorFactId = anchorFactId,
    )
}

enum class MemoryMaintenanceWorkerState {
    IDLE,
    RUNNING,
    BLOCKED,
}

enum class MemoryMaintenanceBlockReason {
    DREAMER_DISABLED,
    NO_PENDING_CLUSTERS,
    NO_DETERMINISTIC_ACTIONS,
}

data class MemoryMaintenanceStatus(
    val preferences: MemoryMaintenancePreferences = MemoryMaintenancePreferences(),
    val workerState: MemoryMaintenanceWorkerState = MemoryMaintenanceWorkerState.IDLE,
    val pendingClusters: Int = 0,
    val blockedClusters: Int = 0,
    val lastAttemptedAt: Instant? = null,
    val lastCompletedAt: Instant? = null,
    val blockedReason: MemoryMaintenanceBlockReason? = null,
    val lastErrorCode: String? = null,
    val availableModels: List<LLMModel> = emptyList(),
)

interface MemoryMaintenanceController {
    suspend fun status(): MemoryMaintenanceStatus

    suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus

    suspend fun runNow(): MemoryMaintenanceStatus

    suspend fun runDue(): MemoryMaintenanceStatus = runNow()
}

object NoopMemoryMaintenanceController : MemoryMaintenanceController {
    override suspend fun status(): MemoryMaintenanceStatus =
        MemoryMaintenanceStatus(blockedReason = MemoryMaintenanceBlockReason.DREAMER_DISABLED)

    override suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus {
        return MemoryMaintenanceStatus(
            preferences = preferences,
            blockedReason = if (preferences.mode == MemoryMaintenanceMode.OFF) {
                MemoryMaintenanceBlockReason.DREAMER_DISABLED
            } else {
                MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS
            },
        )
    }

    override suspend fun runNow(): MemoryMaintenanceStatus =
        status()
}

private const val DREAMER_REGION_CLUSTER_PREFIX = "dreamer-region"

private fun String.toBase64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(UTF_8))

private fun String.fromBase64Url(): String? =
    runCatching { String(Base64.getUrlDecoder().decode(this), UTF_8) }.getOrNull()
