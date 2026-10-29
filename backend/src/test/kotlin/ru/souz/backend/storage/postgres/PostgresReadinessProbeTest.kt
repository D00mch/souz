package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking

class PostgresReadinessProbeTest {
    @Test
    fun `read-only and unreachable databases are not writable`() = runBlocking {
        mapOf(
            "read-only" to containerPool { connectionInitSql = "set default_transaction_read_only = on" },
            "unreachable" to containerPool {
                jdbcUrl = "jdbc:postgresql://127.0.0.1:1/souz"
                connectionTimeout = 250L
                initializationFailTimeout = -1L
            },
        ).forEach { (name, pool) -> pool.use { assertFalse(PostgresReadinessProbe(it).isWritable(), name) } }
    }

    @Test
    fun `a database that does not answer within the deadline is not writable`() = runBlocking {
        containerPool().use { pool ->
            // Answers after 5 s, so a probe that waits for it reports the database as writable.
            val stalled = object : DataSource by pool {
                override fun getConnection() = Thread.sleep(5_000).let { pool.connection }
            }
            val elapsed = measureTime { assertFalse(PostgresReadinessProbe(stalled, timeout = 1.seconds).isWritable()) }
            assertTrue(elapsed < 3.seconds, "Probe ignored the 1 s deadline: $elapsed")
        }
    }

    private fun containerPool(configure: HikariConfig.() -> Unit = {}): HikariDataSource {
        val container = SharedPostgresContainer.instance
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = 2
                configure()
            },
        )
    }
}
