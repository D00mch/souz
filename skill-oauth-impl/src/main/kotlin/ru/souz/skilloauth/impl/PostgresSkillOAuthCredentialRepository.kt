package ru.souz.skilloauth.impl

import javax.sql.DataSource

class PostgresSkillOAuthCredentialRepository(
    private val dataSource: DataSource,
) : SkillOAuthCredentialRepository {
    override suspend fun find(userId: String, provider: String): SkillOAuthCredential? =
        dataSource.read { connection ->
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
        dataSource.write { connection ->
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
                    -- Bumped relative to the *stored* row, not `excluded.revision` (which merely
                    -- carries the revision this write was read at, for the CAS check below).
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
                statement.setString(5, credential.grantedScopes.toScopesColumn())
                statement.setInstant(6, credential.expiresAt)
                statement.setLong(7, credential.generation)
                statement.setLong(8, credential.revision)
                statement.setInstant(9, credential.createdAt)
                statement.setInstant(10, credential.updatedAt)
                statement.executeQuery().use { resultSet ->
                    // No row means the `where` guard rejected the write — a fresher generation
                    // already exists, or a same-generation write already landed since this
                    // credential was read (see SkillOAuthCredentialRepository.upsert's doc comment).
                    if (resultSet.next()) resultSet.toCredential() else null
                }
            }
        }

    override suspend fun delete(userId: String, provider: String) {
        dataSource.write { connection ->
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
            grantedScopes = getString("granted_scopes").fromScopesColumn(),
            expiresAt = instantOrNull("expires_at"),
            generation = getLong("generation"),
            revision = getLong("revision"),
            createdAt = instant("created_at"),
            updatedAt = instant("updated_at"),
        )
}
