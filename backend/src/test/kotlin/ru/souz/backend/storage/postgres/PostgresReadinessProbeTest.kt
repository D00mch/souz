package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import ru.souz.backend.storage.postgres.PostgresReadinessProbe.State

class PostgresReadinessProbeTest {
    @Test
    fun `read-only session is not ready`() = runBlocking {
        containerPool { connectionInitSql = "set default_transaction_read_only = on" }.use { dataSource ->
            assertEquals(State.READ_ONLY, PostgresReadinessProbe(dataSource).check())
        }
    }

    @Test
    fun `unreachable database is unavailable`() = runBlocking {
        containerPool {
            jdbcUrl = "jdbc:postgresql://127.0.0.1:1/souz"
            connectionTimeout = 250L
            initializationFailTimeout = -1L
        }.use { dataSource ->
            assertEquals(State.UNAVAILABLE, PostgresReadinessProbe(dataSource).check())
        }
    }

    @Test
    fun `concurrent probes on an exhausted pool wait for one connection and time out on schedule`() = runBlocking {
        containerPool { maximumPoolSize = 1 }.use { dataSource ->
            val probe = PostgresReadinessProbe(dataSource, timeout = 1.seconds)
            dataSource.connection.use {
                val elapsed = measureTime {
                    val probes = List(2) { async { probe.check() } }
                    delay(500.milliseconds)
                    assertEquals(1, dataSource.hikariPoolMXBean.threadsAwaitingConnection)
                    assertEquals(listOf(State.TIMED_OUT, State.TIMED_OUT), probes.map { it.await() })
                }
                assertTrue(elapsed < 3.seconds, "Probes ignored the 1 s deadline: $elapsed")
            }
            assertEquals(State.WRITABLE, probe.check())
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
                connectionTimeout = 5_000L
                configure()
            },
        )
    }
}
