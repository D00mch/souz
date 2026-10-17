package ru.souz.backend.storage.postgres

import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Answers within [timeout] whether PostgreSQL accepts writes. A JDBC call stuck on a half-open socket ignores
 * interrupts, so the query runs detached; the lock stays held until it ends, keeping readiness to one connection.
 */
internal class PostgresReadinessProbe(
    private val dataSource: DataSource,
    private val timeout: Duration = 2.seconds,
) {
    private val logger = LoggerFactory.getLogger(PostgresReadinessProbe::class.java)
    private val probeMutex = Mutex()
    private val queryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun isWritable(): Boolean {
        val writable = withTimeoutOrNull(timeout) {
            probeMutex.lock()
            queryScope.async {
                try {
                    queryWritable()
                } finally {
                    probeMutex.unlock()
                }
            }.await()
        }
        if (writable == null) logger.warn("PostgreSQL readiness check timed out after {}", timeout)
        return writable == true
    }

    // Logs never include the SQL exception message: the driver puts host names into it.
    private fun queryWritable(): Boolean = try {
        dataSource.connection.use { connection ->
            // Ends a read on a dead socket so the lock is not held until the OS gives up; Hikari resets it on return.
            connection.setNetworkTimeout(Runnable::run, timeout.inWholeMilliseconds.toInt())
            connection.createStatement().use { statement ->
                val result = statement.executeQuery("show transaction_read_only")
                result.next()
                val writable = result.getString(1) == "off"
                if (!writable) logger.warn("PostgreSQL readiness check failed: the database is read-only")
                writable
            }
        }
    } catch (failure: SQLException) {
        logger.warn("PostgreSQL readiness check failed: {} sqlState={}", failure.javaClass.simpleName, failure.sqlState)
        false
    }
}
