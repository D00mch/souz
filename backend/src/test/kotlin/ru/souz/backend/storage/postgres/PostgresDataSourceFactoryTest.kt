package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PostgresDataSourceFactoryTest {
    @Test
    fun `closes data source when initialization fails`() {
        val dataSource = mockk<HikariDataSource>(relaxed = true)
        val migrationFailure = IllegalStateException("migration failed")

        val thrown = assertFailsWith<IllegalStateException> {
            initializeDataSource(dataSource) { throw migrationFailure }
        }

        assertSame(migrationFailure, thrown)
        verify(exactly = 1) { dataSource.close() }
    }

    @Test
    fun `preserves initialization failure when close also fails`() {
        val dataSource = mockk<HikariDataSource>()
        val migrationFailure = IllegalStateException("migration failed")
        val closeFailure = IllegalArgumentException("close failed")
        every { dataSource.close() } throws closeFailure

        val thrown = assertFailsWith<IllegalStateException> {
            initializeDataSource(dataSource) { throw migrationFailure }
        }

        assertSame(migrationFailure, thrown)
        assertEquals(listOf(closeFailure), thrown.suppressed.toList())
        verify(exactly = 1) { dataSource.close() }
    }
}
