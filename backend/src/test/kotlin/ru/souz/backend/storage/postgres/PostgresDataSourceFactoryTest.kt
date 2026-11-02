package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariDataSource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import ru.souz.backend.attachments.attachmentObjectKey
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.impl.PostgresJobService

class PostgresDataSourceFactoryTest {
    @Test
    fun `attachment address migration preserves populated legacy storage across restart`() = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("attachment_upgrade")).postgres
        val container = SharedPostgresContainer.instance
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .defaultSchema(config.schema)
            .schemas(config.schema)
            .target("22")
            .load().migrate()
        val owner = "legacy-Владелец"
        val files = listOf("pending", "ready", "deleting").associateWith { UUID.randomUUID() }
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.schema = config.schema
            connection.prepareStatement("""
                insert into stored_attachments(id, user_id, source_key, name, mime_type, size_bytes, sha256,
                    state, created_at, stored_at, expires_at, cleanup_due_at)
                values (?, ?, ?, 'legacy.txt', 'text/plain', 6, 'legacy-checksum', ?, now(), now(),
                    now() + interval '30 days', now() + interval '30 days')
            """.trimIndent()).use { statement ->
                for ((state, id) in files) {
                    statement.setObject(1, id)
                    statement.setString(2, owner)
                    statement.setString(3, state)
                    statement.setString(4, state)
                    statement.executeUpdate()
                }
            }
        }
        repeat(2) {
            PostgresDataSourceFactory.create(config).use { dataSource ->
                val repository = PostgresAttachmentRepository(dataSource)
                for ((state, id) in files) {
                    val file = requireNotNull(repository.get(owner, id))
                    assertEquals(attachmentObjectKey(owner, id), file.objectKey)
                    assertEquals(state, file.state)
                    assertEquals("legacy.txt", file.name)
                    assertEquals(file, repository.getByObjectKey(owner, file.objectKey))
                }
            }
        }
    }

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
