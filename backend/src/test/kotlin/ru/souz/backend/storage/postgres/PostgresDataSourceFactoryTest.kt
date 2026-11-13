package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariDataSource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.flywaydb.core.Flyway
import ru.souz.backend.settings.model.PersonalityParameters
import ru.souz.backend.settings.model.PersonalitySnapshot
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
            assertEquals(job, PostgresJobService(dataSource).listJobs(owner).items.single())
        }
    }

    @Test
    fun `migrates legacy users including owners without a settings row`() = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("legacy_personality")).postgres
        val pg = SharedPostgresContainer.instance
        Flyway.configure().dataSource(pg.jdbcUrl, pg.username, pg.password).schemas(config.schema)
            .locations("classpath:db/migration").target("25").load().migrate()
        pg.createConnection("").use { connection ->
            connection.schema = config.schema
            connection.createStatement().use {
                it.executeUpdate("insert into users(id) values ('custom'), ('defaults')")
                it.executeUpdate("""insert into user_settings(user_id, settings_json, personality_prompt, personality_revision)
                    values ('custom', '{}', 'My style', 12)""")
            }
        }
        PostgresDataSourceFactory.create(config).use { ds ->
            val repo = PostgresUserSettingsRepository(ds)
            assertEquals(PersonalitySnapshot(PersonalityParameters(personality = "My style"), 12), repo.personality("custom"))
            assertEquals(PersonalitySnapshot(), repo.personality("defaults"))
            assertEquals(null, repo.get("defaults"))
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
