package ru.souz.backend.storage.postgres

import javax.sql.DataSource
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.llms.LlmProvider

class PostgresUserProviderKeyRepository(
    private val dataSource: DataSource,
) : UserProviderKeyRepository {
    override suspend fun get(
        userId: String,
        provider: LlmProvider,
    ): UserProviderKey? = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select *
            from user_provider_keys
            where user_id = ? and provider = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, provider.name)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toUserProviderKeyOrNull() else null
            }
        }
    }

    override suspend fun list(userId: String): List<UserProviderKey> = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select *
            from user_provider_keys
            where user_id = ?
            order by provider asc
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        resultSet.toUserProviderKeyOrNull()?.let(::add)
                    }
                }
            }
        }
    }

    override suspend fun save(key: UserProviderKey): UserProviderKey = dataSource.write { connection ->
        connection.prepareStatement(
            """
            insert into user_provider_keys(
                user_id,
                provider,
                encrypted_api_key,
                key_hint,
                created_at,
                updated_at
            )
            values (?, ?, ?, ?, ?, ?)
            on conflict (user_id, provider) do update
            set encrypted_api_key = excluded.encrypted_api_key,
                key_hint = excluded.key_hint,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, key.userId)
            statement.setString(2, key.provider.name)
            statement.setBytes(3, key.encryptedApiKey.toByteArray(Charsets.UTF_8))
            statement.setString(4, key.keyHint)
            statement.setInstant(5, key.createdAt)
            statement.setInstant(6, key.updatedAt)
            statement.executeUpdate()
        }
        key
    }

    override suspend fun delete(
        userId: String,
        provider: LlmProvider,
    ): Boolean = dataSource.write { connection ->
        connection.prepareStatement(
            """
            delete from user_provider_keys
            where user_id = ? and provider = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, provider.name)
            statement.executeUpdate() > 0
        }
    }
}
