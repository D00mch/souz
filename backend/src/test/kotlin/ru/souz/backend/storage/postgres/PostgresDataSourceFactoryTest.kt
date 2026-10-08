package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariDataSource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.impl.PostgresJobService

class PostgresDataSourceFactoryTest {
    @Test
    fun `host datasource applies job migrations and preserves jobs across restart`() = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("job_migrations")).postgres
        val owner = "standalone-job-owner"
        val job = PostgresDataSourceFactory.create(config).use { dataSource ->
            PostgresJobService(dataSource).createJob(
                owner, CreateJobRequest("Persisted job", jacksonObjectMapper().readTree("{\"value\":42}")),
            )
        }

        PostgresDataSourceFactory.create(config).use { dataSource ->
            assertEquals(job, PostgresJobService(dataSource).listJobs(owner).single())
        }
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
