package ru.souz.memory

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.souz.db.ConfigStore
import ru.souz.llms.restJsonMapper
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

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

data class MemoryMaintenanceRunResult(
    val processedJobs: Int,
    val inspectedJobs: Int = processedJobs,
    val blockedJobs: Int = 0,
)

private data class MaintenanceJob(
    val clusterKey: String,
    val ownerId: String,
    val latestDirtyAt: String,
    val reasons: String,
)

class MemoryMaintenanceWorker(
    private val dbPath: Path,
) {
    suspend fun runOnce(preferences: MemoryMaintenancePreferences): MemoryMaintenanceRunResult {
        if (preferences.mode == MemoryMaintenanceMode.OFF) {
            return MemoryMaintenanceRunResult(processedJobs = 0)
        }
        initializeSqliteMemoryRepository(dbPath)
        return withContext(Dispatchers.IO) {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").use { connection ->
                val now = Instant.now().toString()
                val jobs = connection.prepareStatement(
                    """
                    select cluster_key, owner_id, latest_dirty_at, reasons from memory_maintenance_jobs
                    where status = 'PENDING'
                    order by priority desc, latest_dirty_at desc
                    limit ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setInt(1, preferences.maxClustersPerRun.coerceAtLeast(1))
                    statement.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    MaintenanceJob(
                                        clusterKey = rs.getString("cluster_key"),
                                        ownerId = rs.getString("owner_id"),
                                        latestDirtyAt = rs.getString("latest_dirty_at"),
                                        reasons = rs.getString("reasons").orEmpty(),
                                    )
                                )
                            }
                        }
                    }
                }
                if (jobs.isEmpty()) {
                    return@withContext MemoryMaintenanceRunResult(processedJobs = 0)
                }
                val run = connection.inTransaction {
                    var processed = 0
                    var blocked = 0
                    jobs.forEach { job ->
                        if (job.isLegacyChatMigration()) {
                            processed += processLegacyChatMigration(job, now)
                        } else {
                            blocked += blockUnsupportedJob(job, now)
                        }
                    }
                    MemoryMaintenanceRunResult(processedJobs = processed, inspectedJobs = jobs.size, blockedJobs = blocked)
                }
                run
            }
        }
    }

    private fun Connection.processLegacyChatMigration(
        job: MaintenanceJob,
        now: String,
    ): Int {
        val scopeId = job.legacyChatScopeId() ?: return blockUnsupportedJob(job, now)
        val jobUpdated = prepareStatement(
            """
            update memory_maintenance_jobs
            set status = 'DONE',
                attempt_count = attempt_count + 1,
                lease_owner = null,
                lease_expires_at = null,
                updated_at = ?
            where cluster_key = ? and status = 'PENDING'
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, now)
            statement.setString(2, job.clusterKey)
            statement.executeUpdate()
        }
        if (jobUpdated == 0) return 0

        val factIds = prepareStatement(
            """
            select id from memory_facts
            where owner_id = ?
              and scope_type in ('chat', 'thread')
              and scope_id = ?
              and status = ?
              and updated_at <= ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, job.ownerId)
            statement.setString(2, scopeId)
            statement.setString(3, MemoryFactStatus.ACTIVE.name)
            statement.setString(4, job.latestDirtyAt)
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.getString("id"))
                }
            }
        }

        prepareStatement(
            """
            update memory_facts
            set status = ?,
                updated_at = ?
            where id = ? and status = ?
            """.trimIndent()
        ).use { statement ->
            factIds.forEach { factId ->
                statement.setString(1, MemoryFactStatus.RETIRED.name)
                statement.setString(2, now)
                statement.setString(3, factId)
                statement.setString(4, MemoryFactStatus.ACTIVE.name)
                statement.addBatch()
            }
            statement.executeBatch()
        }

        recordMaintenanceOperation(
            ownerId = job.ownerId,
            reason = "done:${job.clusterKey}:legacy_chat_fact_migration:retired=${factIds.size}",
            now = now,
        )
        return 1
    }

    private fun Connection.blockUnsupportedJob(
        job: MaintenanceJob,
        now: String,
    ): Int {
        val blockReason = "no_deterministic_action"
        val updated = prepareStatement(
            """
            update memory_maintenance_jobs
            set status = 'BLOCKED',
                attempt_count = attempt_count + 1,
                lease_owner = null,
                lease_expires_at = null,
                reasons = case
                    when instr(reasons, ?) > 0 then reasons
                    when trim(reasons) = '' then ?
                    else reasons || ',' || ?
                end,
                updated_at = ?
            where cluster_key = ? and status = 'PENDING'
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, blockReason)
            statement.setString(2, blockReason)
            statement.setString(3, blockReason)
            statement.setString(4, now)
            statement.setString(5, job.clusterKey)
            statement.executeUpdate()
        }
        if (updated > 0) {
            recordMaintenanceOperation(
                ownerId = job.ownerId,
                reason = "blocked:${job.clusterKey}:$blockReason",
                now = now,
            )
        }
        return updated
    }

    private fun Connection.recordMaintenanceOperation(
        ownerId: String,
        reason: String,
        now: String,
    ) {
        prepareStatement(
            """
            insert into memory_operation_log(id, fact_id, owner_id, type, reason, created_at)
            values (?, null, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, ownerId)
            statement.setString(3, MemoryOperationType.MAINTENANCE.name)
            statement.setString(4, reason)
            statement.setString(5, now)
            statement.executeUpdate()
        }
    }

    private fun MaintenanceJob.isLegacyChatMigration(): Boolean =
        reasons.split(',').map(String::trim).contains("legacy_chat_fact_migration") &&
            legacyChatScopeId() != null

    private fun MaintenanceJob.legacyChatScopeId(): String? {
        val raw = clusterKey.removePrefix("legacy-chat:").takeIf { it != clusterKey } ?: return null
        return raw.substringAfter(':', missingDelimiterValue = "").takeIf(String::isNotBlank)
    }

    private inline fun <T> Connection.inTransaction(block: Connection.() -> T): T {
        autoCommit = false
        return try {
            block().also { commit() }
        } catch (error: Exception) {
            rollback()
            throw error
        }
    }
}

class DesktopMemoryMaintenanceController(
    private val dbPath: Path,
    private val settingsStore: MemoryMaintenanceSettingsStore = ConfigStoreMemoryMaintenanceSettingsStore,
    private val worker: MemoryMaintenanceWorker = MemoryMaintenanceWorker(dbPath),
) : MemoryMaintenanceController {
    override suspend fun status(): MemoryMaintenanceStatus {
        val preferences = loadPreferences()
        return statusOrError(preferences)
    }

    override suspend fun savePreferences(preferences: MemoryMaintenancePreferences): MemoryMaintenanceStatus {
        settingsStore.put(PREFERENCES_KEY, restJsonMapper.writeValueAsString(preferences))
        return statusOrError(preferences)
    }

    override suspend fun runNow(): MemoryMaintenanceStatus {
        val preferences = loadPreferences()
        val now = Instant.now()
        settingsStore.put(LAST_ATTEMPTED_AT_KEY, now.toString())
        if (preferences.mode == MemoryMaintenanceMode.OFF) {
            return statusOrError(preferences, attemptedAt = now)
        }
        return try {
            val run = worker.runOnce(preferences)
            settingsStore.put(LAST_ERROR_CODE_KEY, "")
            if (run.processedJobs > 0) {
                settingsStore.put(LAST_COMPLETED_AT_KEY, Instant.now().toString())
                settingsStore.put(LAST_NO_ACTION_AT_KEY, "")
            } else if (run.inspectedJobs > 0 || run.blockedJobs > 0) {
                settingsStore.put(LAST_NO_ACTION_AT_KEY, now.toString())
            }
            statusOrError(preferences, attemptedAt = now)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            settingsStore.put(LAST_ERROR_CODE_KEY, error.errorCode())
            statusForError(preferences, error, attemptedAt = now)
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
        val usage = loadCloudUsageForToday()
        return MemoryMaintenanceStatus(
            preferences = preferences,
            pendingClusters = pendingClusters,
            blockedClusters = blockedClusters,
            cloudTokensUsedToday = usage.tokens,
            cloudCallsUsedToday = usage.calls,
            lastAttemptedAt = attemptedAt,
            lastCompletedAt = readInstant(LAST_COMPLETED_AT_KEY),
            lastErrorCode = readString(LAST_ERROR_CODE_KEY),
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
        )

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

    private suspend fun loadCloudUsageForToday(): CloudUsage = withContext(Dispatchers.IO) {
        val periodKey = LocalDate.now(ZoneOffset.UTC).toString()
        val calls = queryInt(
            sql = "select coalesce(sum(call_count), 0) from memory_budget_reservations where period_key = ? and status != 'CANCELLED'",
            params = listOf(periodKey),
        )
        val tokens = queryInt(
            sql = """
                select coalesce(sum(coalesce(actual_input_tokens, estimated_input_tokens) +
                    coalesce(actual_output_tokens, reserved_output_tokens)), 0)
                from memory_budget_reservations
                where period_key = ? and status != 'CANCELLED'
            """.trimIndent(),
            params = listOf(periodKey),
        )
        CloudUsage(tokens = tokens, calls = calls)
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

    private data class CloudUsage(
        val tokens: Int,
        val calls: Int,
    )

    private companion object {
        const val PREFERENCES_KEY = "MEMORY_MAINTENANCE_PREFERENCES"
        const val LAST_ATTEMPTED_AT_KEY = "MEMORY_MAINTENANCE_LAST_ATTEMPTED_AT"
        const val LAST_COMPLETED_AT_KEY = "MEMORY_MAINTENANCE_LAST_COMPLETED_AT"
        const val LAST_ERROR_CODE_KEY = "MEMORY_MAINTENANCE_LAST_ERROR_CODE"
        const val LAST_NO_ACTION_AT_KEY = "MEMORY_MAINTENANCE_LAST_NO_ACTION_AT"
    }
}
