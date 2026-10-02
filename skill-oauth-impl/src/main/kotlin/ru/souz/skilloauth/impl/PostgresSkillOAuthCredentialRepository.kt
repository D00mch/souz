package ru.souz.skilloauth.impl

import javax.sql.DataSource

class PostgresSkillOAuthCredentialRepository(
    private val dataSource: DataSource,
) : SkillOAuthCredentialRepository {
    override suspend fun find(userId: String, provider: String): SkillOAuthCredential? =
        dataSource.withConnection { connection ->
            connection.prepareStatement(
                "select * from skill_oauth_credentials where user_id = ? and provider = ?"
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, provider)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.toCredential() else null
                }
            }
        }

    override suspend fun upsert(credential: SkillOAuthCredential): SkillOAuthCredential? =
        dataSource.withConnection { connection ->
            connection.autoCommit = true
            connection.prepareStatement(
                """
                insert into skill_oauth_credentials(
                    user_id, provider, access_token_encrypted, refresh_token_encrypted,
                    granted_scopes, expires_at, generation, revision, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (user_id, provider) do update
                set access_token_encrypted = excluded.access_token_encrypted,
                    refresh_token_encrypted = excluded.refresh_token_encrypted,
                    granted_scopes = excluded.granted_scopes,
                    expires_at = excluded.expires_at,
                    generation = excluded.generation,
                    -- Revision is the stored row's CAS token, incremented on every accepted write.
                    revision = skill_oauth_credentials.revision + 1,
                    updated_at = excluded.updated_at
                where excluded.generation > skill_oauth_credentials.generation
                   or (
                        excluded.generation = skill_oauth_credentials.generation
                        and excluded.revision = skill_oauth_credentials.revision
                   )
                returning *
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, credential.userId)
                statement.setString(2, credential.provider)
                statement.setString(3, credential.accessTokenEncrypted)
                statement.setString(4, credential.refreshTokenEncrypted)
                statement.setScopes(5, credential.grantedScopes)
                statement.setInstant(6, credential.expiresAt)
                statement.setLong(7, credential.generation)
                statement.setLong(8, credential.revision)
                statement.setInstant(9, credential.createdAt)
                statement.setInstant(10, credential.updatedAt)
                statement.executeQuery().use { resultSet ->
                    // A newer generation or revision rejects the write without changing the row.
                    if (resultSet.next()) resultSet.toCredential() else null
                }
            }
        }

    override suspend fun delete(userId: String, provider: String) {
        dataSource.withConnection { connection ->
            connection.autoCommit = true
            connection.prepareStatement(
                "delete from skill_oauth_credentials where user_id = ? and provider = ?"
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, provider)
                statement.executeUpdate()
            }
        }
    }

    private fun java.sql.ResultSet.toCredential(): SkillOAuthCredential =
        SkillOAuthCredential(
            userId = getString("user_id"),
            provider = getString("provider"),
            accessTokenEncrypted = getString("access_token_encrypted"),
            refreshTokenEncrypted = getString("refresh_token_encrypted"),
            grantedScopes = scopes("granted_scopes"),
            expiresAt = instantOrNull("expires_at"),
            generation = getLong("generation"),
            revision = getLong("revision"),
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
        )
}
