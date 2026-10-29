package ru.souz.backend.storage.postgres

import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

internal class PostgresReadinessProbe(
    private val dataSource: DataSource,
    private val timeout: Duration = 2.seconds,
) {
    private val logger = LoggerFactory.getLogger(PostgresReadinessProbe::class.java)

    suspend fun isWritable(): Boolean =
        withTimeoutOrNull(timeout) { runInterruptible(Dispatchers.IO) { queryWritable() } }
            ?: false.also { logger.warn("PostgreSQL readiness check timed out after {}", timeout) }

    // Logs never include the SQL exception message: the driver puts host names into it.
    private fun queryWritable(): Boolean = try {
        dataSource.connection.use { connection ->
            // A read on a dead socket ignores interrupts; this ends it instead of the OS. Hikari resets it on return.
            connection.setNetworkTimeout(Runnable::run, timeout.inWholeMilliseconds.toInt())
            connection.createStatement().use { statement ->
                val writable = statement.executeQuery("show transaction_read_only").run { next(); getString(1) == "off" }
                if (!writable) logger.warn("PostgreSQL readiness check failed: the database is read-only")
                writable
            }
        }
    } catch (failure: SQLException) {
        logger.warn("PostgreSQL readiness check failed: {} sqlState={}", failure.javaClass.simpleName, failure.sqlState)
        false
    }
}
