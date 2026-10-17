package ru.souz.backend.storage.postgres

import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Bounded check that the backend's PostgreSQL dependency accepts writes: one pooled connection,
 * one `SHOW transaction_read_only`, and a hard deadline.
 *
 * Probes are serialized so concurrent readiness requests never hold more than one pooled
 * connection; a probe that cannot even take the lock before the deadline reports a timeout
 * without touching the pool.
 */
internal class PostgresReadinessProbe(
    private val dataSource: DataSource,
    private val timeout: Duration = 2.seconds,
) {
    enum class State { WRITABLE, READ_ONLY, UNAVAILABLE, TIMED_OUT }

    private val logger = LoggerFactory.getLogger(PostgresReadinessProbe::class.java)
    private val probeMutex = Mutex()
    private val queryTimeoutSeconds = timeout.toInt(DurationUnit.SECONDS).coerceAtLeast(1)

    suspend fun check(): State {
        val state = withTimeoutOrNull(timeout) {
            probeMutex.withLock {
                runInterruptible(Dispatchers.IO) { queryState() }
            }
        } ?: State.TIMED_OUT
        if (state == State.READ_ONLY || state == State.TIMED_OUT) {
            logger.warn("PostgreSQL readiness check failed: {}", state)
        }
        return state
    }

    // Logs never include the SQL exception message: the driver puts host names into it.
    private fun queryState(): State = try {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = queryTimeoutSeconds
                statement.executeQuery("show transaction_read_only").use { result ->
                    result.next()
                    if (result.getString(1) == "off") State.WRITABLE else State.READ_ONLY
                }
            }
        }
    } catch (failure: SQLException) {
        logger.warn(
            "PostgreSQL readiness check failed: {} sqlState={}",
            failure.javaClass.simpleName,
            failure.sqlState,
        )
        State.UNAVAILABLE
    }
}
