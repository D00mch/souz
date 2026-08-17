package ru.souz.backend.storage.postgres

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresBackendServerPreferenceStoreTest {
    @Test
    fun `server preference store encrypts values at rest and decrypts on read`() {
        val schema = newPostgresSchema("postgres_backend_server_preferences")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)
        val store = PostgresBackendServerPreferenceStore(
            dataSource = dataSource,
            masterKey = "test-master-key",
        )

        dataSource.use {
            store.put("CODEX_ACCESS_TOKEN", "secret-access-token")

            val raw = dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    select encrypted_value
                    from backend_server_preferences
                    where preference_key = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, "CODEX_ACCESS_TOKEN")
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getBytes("encrypted_value").toString(Charsets.UTF_8)
                    }
                }
            }

            assertNotEquals("secret-access-token", raw)
            assertTrue(raw.startsWith("enc:v1:"))
            assertEquals("secret-access-token", store.get("CODEX_ACCESS_TOKEN"))

            store.remove("CODEX_ACCESS_TOKEN")

            assertNull(store.get("CODEX_ACCESS_TOKEN"))
        }
    }
}
