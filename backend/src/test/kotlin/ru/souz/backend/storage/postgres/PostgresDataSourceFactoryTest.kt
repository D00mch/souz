package ru.souz.backend.storage.postgres

import com.zaxxer.hikari.HikariDataSource
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.souz.backend.settings.model.PersonalityParameters
import ru.souz.backend.settings.model.PersonalitySnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import ru.souz.backend.attachments.attachmentObjectKey
import ru.souz.jobs.CreateJobRequest
import ru.souz.jobs.impl.PostgresJobService

class PostgresDataSourceFactoryTest {
    @Test
    fun `bot removal migration drops populated bindings and preserves chats and messages across restart`() = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("bot_removal_upgrade")).postgres
        val container = SharedPostgresContainer.instance
        Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations("classpath:db/migration")
            .defaultSchema(config.schema)
            .schemas(config.schema)
            .target("26")
            .load().migrate()
        val owner = "bot-migration-owner"
        val chats = listOf(UUID.randomUUID(), UUID.randomUUID())
        container.createConnection("").use { connection ->
            connection.schema = config.schema
            connection.createStatement().use { statement ->
                statement.executeUpdate("insert into users(id) values ('$owner')")
                for (chat in chats) {
                    statement.executeUpdate("""insert into chats(id, user_id, client_type, request_id, payload_hash)
                        values ('$chat', '$owner', 'backend', '$chat', 'hash')""")
                    statement.executeUpdate("""insert into messages(id, user_id, chat_id, seq, role, content)
                        values ('${UUID.randomUUID()}', '$owner', '$chat', 1, 'user', 'Keep this history')""")
                }
                statement.executeUpdate("""insert into telegram_bot_bindings(
                    id, user_id, chat_id, bot_token_encrypted, bot_token_hash, created_at, updated_at)
                    values ('${UUID.randomUUID()}', '$owner', '${chats[0]}', 'encrypted', 'hash', now(), now())""")
                statement.executeUpdate("""insert into vk_bot_bindings(
                    id, user_id, chat_id, group_token_encrypted, group_token_hash, vk_group_id, created_at, updated_at)
                    values ('${UUID.randomUUID()}', '$owner', '${chats[1]}', 'encrypted', 'hash', 123, now(), now())""")
            }
        }
        repeat(2) {
            PostgresDataSourceFactory.create(config).use { dataSource ->
                dataSource.read { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select to_regclass('telegram_bot_bindings'), to_regclass('vk_bot_bindings')").use {
                            it.next()
                            assertNull(it.getString(1))
                            assertNull(it.getString(2))
                        }
                        statement.executeQuery("select c.id, m.content from chats c join messages m on m.chat_id = c.id").use {
                            val retained = buildMap {
                                while (it.next()) put(it.getObject(1, UUID::class.java), it.getString(2))
                            }
                            assertEquals(chats.associateWith { "Keep this history" }, retained)
                        }
                    }
                }
            }
        }
    }

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
