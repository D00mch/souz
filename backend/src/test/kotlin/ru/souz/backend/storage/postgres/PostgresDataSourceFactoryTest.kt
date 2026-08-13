package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PostgresDataSourceFactoryTest {
    @Test
    fun `migration failure closes newly created data source`() {
        val dataSource = RecordingHikariDataSource()
        val migrationFailure = IllegalStateException("migration failed")

        val thrown = assertFailsWith<IllegalStateException> {
            PostgresDataSourceFactory.migrate(dataSource, "test_schema") { _, _ ->
                throw migrationFailure
            }
        }

        assertSame(migrationFailure, thrown)
        assertEquals(1, dataSource.closeCalls)
    }

    @Test
    fun `data source close failure is suppressed by migration failure`() {
        val closeFailure = IllegalArgumentException("close failed")
        val dataSource = RecordingHikariDataSource(closeFailure)
        val migrationFailure = IllegalStateException("migration failed")

        val thrown = assertFailsWith<IllegalStateException> {
            PostgresDataSourceFactory.migrate(dataSource, "test_schema") { _, _ ->
                throw migrationFailure
            }
        }

        assertSame(migrationFailure, thrown)
        assertEquals(1, dataSource.closeCalls)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
    }

    private class RecordingHikariDataSource(
        private val closeFailure: Throwable? = null,
    ) : HikariDataSource() {
        var closeCalls: Int = 0
            private set

        override fun close() {
            closeCalls += 1
            closeFailure?.let { throw it }
            super.close()
        }
    }
}
