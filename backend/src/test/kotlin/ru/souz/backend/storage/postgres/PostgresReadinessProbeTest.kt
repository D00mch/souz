package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.async
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
    fun `concurrent probes on a stalled database make one connection attempt and answer on schedule`() = runBlocking {
        containerPool().use { pool ->
            val release = CountDownLatch(1)
            val attempts = AtomicInteger()
            // A database that stops answering; it gives up after 5 s so a regression fails instead of hanging.
            val stalled = object : DataSource by pool {
                override fun getConnection(): Connection {
                    attempts.incrementAndGet()
                    release.await(5, TimeUnit.SECONDS)
                    return pool.connection
                }
            }
            val probe = PostgresReadinessProbe(stalled, timeout = 1.seconds)
            val elapsed = measureTime {
                assertEquals(listOf(false, false), List(2) { async { probe.isWritable() } }.map { it.await() })
            }
            assertTrue(elapsed < 3.seconds, "Probes ignored the 1 s deadline: $elapsed")
            assertEquals(1, attempts.get())
            release.countDown()
            assertTrue(probe.isWritable())
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
