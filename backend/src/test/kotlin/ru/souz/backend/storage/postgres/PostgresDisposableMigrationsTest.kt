package ru.souz.backend.storage.postgres

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PostgresDisposableMigrationsTest {
    @Test
    fun `V10 installs disposable backend durable tables and database invariants`() = runTest {
        val schema = newPostgresSchema("postgres_disposable_migrations")

        PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres).use { dataSource ->
            val durableStateMigrationApplied = dataSource.read { connection ->
                connection.prepareStatement(
                    """
                    select exists(
                      select 1
                      from flyway_schema_history
                      where version = '10' and success
                    )
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getBoolean(1)
                    }
                }
            }
            assertTrue(durableStateMigrationApplied)

            val durableTables = listOf(
                "skill_bundles",
                "user_skill_registrations",
                "skill_validations",
                "conversation_knowledge",
                "backend_mutable_credentials",
            )
            durableTables.forEach { table ->
                assertNotNull(registeredTable(dataSource, table), "Missing migrated table $table")
            }

            val knowledgeForeignKey = constraintDefinition(
                dataSource,
                "conversation_knowledge_chat_fk",
            )
            assertTrue(knowledgeForeignKey.contains("FOREIGN KEY (user_id, chat_id)"))
            assertTrue(knowledgeForeignKey.contains("REFERENCES chats(user_id, id) ON DELETE CASCADE"))
            assertTrue(constraintDefinition(dataSource, "conversation_knowledge_content_shape").contains("CHECK"))
            assertTrue(
                constraintDefinition(dataSource, "backend_mutable_credentials_known_key")
                    .contains("codex_oauth")
            )

            val plaintextFailure = assertFailsWith<SQLException> {
                dataSource.write { connection ->
                    connection.prepareStatement(
                        """
                        insert into backend_mutable_credentials(credential_key, encrypted_payload, version)
                        values ('codex_oauth', convert_to('plaintext', 'UTF8'), 0)
                        """.trimIndent()
                    ).use { statement -> statement.executeUpdate() }
                }
            }
            assertEquals(CHECK_VIOLATION_SQL_STATE, plaintextFailure.sqlState)
        }
    }

    private suspend fun registeredTable(
        dataSource: javax.sql.DataSource,
        table: String,
    ): String? = dataSource.read { connection ->
        connection.prepareStatement("select to_regclass(?)::text").use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getString(1)
            }
        }
    }

    private suspend fun constraintDefinition(
        dataSource: javax.sql.DataSource,
        constraintName: String,
    ): String = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select pg_get_constraintdef(oid)
            from pg_constraint
            where connamespace = current_schema()::regnamespace and conname = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, constraintName)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next(), "Missing constraint $constraintName")
                resultSet.getString(1)
            }
        }
    }

    private companion object {
        const val CHECK_VIOLATION_SQL_STATE = "23514"
    }
}
