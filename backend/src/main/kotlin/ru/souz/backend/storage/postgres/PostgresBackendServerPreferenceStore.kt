package ru.souz.backend.storage.postgres

import javax.sql.DataSource
import ru.souz.backend.settings.repository.BackendServerPreferenceStore
import ru.souz.db.AesGcmSecretCodec

class PostgresBackendServerPreferenceStore(
    private val dataSource: DataSource,
    private val masterKey: String,
) : BackendServerPreferenceStore {
    override fun get(key: String): String? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select encrypted_value
                from backend_server_preferences
                where preference_key = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        AesGcmSecretCodec.decrypt(
                            masterKey = masterKey,
                            payload = resultSet.getBytes("encrypted_value").toString(Charsets.UTF_8),
                        )
                    } else {
                        null
                    }
                }
            }
        }

    override fun put(key: String, value: String) {
        val encryptedValue = AesGcmSecretCodec.encrypt(masterKey = masterKey, plainText = value)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into backend_server_preferences(preference_key, encrypted_value)
                values (?, ?)
                on conflict (preference_key) do update
                set encrypted_value = excluded.encrypted_value,
                    updated_at = now()
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, key)
                statement.setBytes(2, encryptedValue.toByteArray(Charsets.UTF_8))
                statement.executeUpdate()
            }
        }
    }

    override fun remove(key: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                delete from backend_server_preferences
                where preference_key = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, key)
                statement.executeUpdate()
            }
        }
    }
}
