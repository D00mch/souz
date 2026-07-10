package ru.souz.memory

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import ru.souz.db.ConfigStore
import ru.souz.llms.restJsonMapper
import ru.souz.llms.LLMModel
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

interface MemoryMaintenanceSettingsStore {
    fun put(key: String, value: String)

    fun get(key: String): String?
}

object ConfigStoreMemoryMaintenanceSettingsStore : MemoryMaintenanceSettingsStore {
    override fun put(key: String, value: String) {
        ConfigStore.put(key, value)
    }

    override fun get(key: String): String? = ConfigStore.get<String>(key)
}

class DesktopMemoryMaintenanceController(
    private val dbPath: Path,
    private val settingsStore: MemoryMaintenanceSettingsStore = ConfigStoreMemoryMaintenanceSettingsStore,
    private val worker: MemoryMaintenanceWorker = MemoryMaintenanceWorker(dbPath),
    private val availableModels: () -> List<LLMModel> = { emptyList() },
) : MemoryMaintenanceController {
    private val runMutex = Mutex()

    override suspend fun status(): MemoryMaintenanceStatus {
        val preferences = loadPreferences()
        return statusOrError(preferences)
    }

    override suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus {
        val normalized = preferences.withAvailableModel()
        settingsStore.put(PREFERENCES_KEY, restJsonMapper.writeValueAsString(normalized))
        if (normalized.mode == MemoryMaintenanceMode.OFF) {
            settingsStore.put(LAST_ERROR_CODE_KEY, "")
        }
        return statusOrError(normalized)
    }

    override suspend fun runNow(): MemoryMaintenanceStatus = run(ignoreBackoff = true)

    override suspend fun runDue(): MemoryMaintenanceStatus = run(ignoreBackoff = false)

    private suspend fun run(ignoreBackoff: Boolean): MemoryMaintenanceStatus {
        val preferences = loadPreferences()
        val now = Instant.now()
        if (!runMutex.tryLock()) {
            return statusOrError(preferences, attemptedAt = now).copy(workerState = MemoryMaintenanceWorkerState.RUNNING)
        }
        settingsStore.put(LAST_ATTEMPTED_AT_KEY, now.toString())
        return try {
            if (preferences.mode == MemoryMaintenanceMode.OFF) {
                statusOrError(preferences, attemptedAt = now)
            } else {
                val processedJobs = worker.runOnce(preferences, ignoreBackoff)
                settingsStore.put(LAST_ERROR_CODE_KEY, "")
                if (processedJobs > 0) {
                    settingsStore.put(LAST_COMPLETED_AT_KEY, Instant.now().toString())
                }
                statusOrError(preferences, attemptedAt = now)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            settingsStore.put(LAST_ERROR_CODE_KEY, error.errorCode())
            statusForError(preferences, error, attemptedAt = now)
        } finally {
            runMutex.unlock()
        }
    }

    private fun loadPreferences(): MemoryMaintenancePreferences =
        settingsStore.get(PREFERENCES_KEY)
            ?.takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { restJsonMapper.readValue<MemoryMaintenancePreferences>(raw) }.getOrNull() }
            ?: MemoryMaintenancePreferences()

    private suspend fun statusFor(
        preferences: MemoryMaintenancePreferences,
        attemptedAt: Instant? = readInstant(LAST_ATTEMPTED_AT_KEY),
    ): MemoryMaintenanceStatus {
        initializeSqliteMemoryRepository(dbPath)
        val disabled = preferences.mode == MemoryMaintenanceMode.OFF
        val pendingClusters = countMaintenanceJobs("PENDING")
        val blockedClusters = countMaintenanceJobs("BLOCKED")
        return MemoryMaintenanceStatus(
            preferences = preferences,
            workerState = if (runMutex.isLocked) {
                MemoryMaintenanceWorkerState.RUNNING
            } else {
                MemoryMaintenanceWorkerState.IDLE
            },
            pendingClusters = pendingClusters,
            blockedClusters = blockedClusters,
            lastAttemptedAt = attemptedAt,
            lastCompletedAt = readInstant(LAST_COMPLETED_AT_KEY),
            lastErrorCode = readString(LAST_ERROR_CODE_KEY),
            availableModels = availableModels.safeValue(),
            blockedReason = if (disabled) {
                MemoryMaintenanceBlockReason.DREAMER_DISABLED
            } else if (pendingClusters > 0) {
                null
            } else if (blockedClusters > 0) {
                MemoryMaintenanceBlockReason.NO_DETERMINISTIC_ACTIONS
            } else {
                MemoryMaintenanceBlockReason.NO_PENDING_CLUSTERS
            },
        )
    }

    private fun statusForError(
        preferences: MemoryMaintenancePreferences,
        error: Throwable,
        attemptedAt: Instant? = readInstant(LAST_ATTEMPTED_AT_KEY),
    ): MemoryMaintenanceStatus =
        MemoryMaintenanceStatus(
            preferences = preferences,
            workerState = MemoryMaintenanceWorkerState.BLOCKED,
            lastAttemptedAt = attemptedAt,
            lastCompletedAt = readInstant(LAST_COMPLETED_AT_KEY),
            lastErrorCode = error.errorCode(),
            availableModels = availableModels.safeValue(),
        )

    private fun MemoryMaintenancePreferences.withAvailableModel(): MemoryMaintenancePreferences {
        val models = availableModels.safeValue()
        if (models.isEmpty() || modelAlias == null) return this
        return copy(modelAlias = modelAlias.takeIf { alias -> models.any { it.alias == alias } })
    }

    private fun (() -> List<LLMModel>).safeValue(): List<LLMModel> =
        runCatching { invoke() }.getOrDefault(emptyList())

    private suspend fun statusOrError(
        preferences: MemoryMaintenancePreferences,
        attemptedAt: Instant? = readInstant(LAST_ATTEMPTED_AT_KEY),
    ): MemoryMaintenanceStatus = try {
        statusFor(preferences, attemptedAt)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        statusForError(preferences, error, attemptedAt)
    }

    private fun readInstant(key: String): Instant? =
        readString(key)?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull()
        }

    private fun readString(key: String): String? =
        settingsStore.get(key)?.trim()?.takeIf(String::isNotBlank)

    private suspend fun countMaintenanceJobs(status: String): Int = withContext(Dispatchers.IO) {
        queryInt(
            sql = "select count(*) from memory_maintenance_jobs where status = ?",
            params = listOf(status),
        )
    }

    private fun queryInt(sql: String, params: List<String>): Int =
        run {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        }.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                params.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }

    private fun Throwable.errorCode(): String =
        this::class.simpleName ?: this::class.qualifiedName?.substringAfterLast('.') ?: "MemoryMaintenanceError"

    private companion object {
        const val PREFERENCES_KEY = "MEMORY_MAINTENANCE_PREFERENCES"
        const val LAST_ATTEMPTED_AT_KEY = "MEMORY_MAINTENANCE_LAST_ATTEMPTED_AT"
        const val LAST_COMPLETED_AT_KEY = "MEMORY_MAINTENANCE_LAST_COMPLETED_AT"
        const val LAST_ERROR_CODE_KEY = "MEMORY_MAINTENANCE_LAST_ERROR_CODE"
    }
}
