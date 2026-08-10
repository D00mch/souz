package ru.souz.skilloauth.impl

import java.time.Instant
import javax.sql.DataSource

class PostgresSkillOAuthPendingStateRepository(
    private val dataSource: DataSource,
) : SkillOAuthPendingStateRepository {
    override suspend fun upsertSupersedingByUserAndProvider(
        pending: SkillOAuthPendingState,
    ): SkillOAuthPendingState =
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                insert into skill_oauth_pending_states(
                    state, user_id, skill_id, provider, requested_scopes, generation, expires_at
                )
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (user_id, provider) do update set
                    state = excluded.state,
                    skill_id = excluded.skill_id,
                    requested_scopes = excluded.requested_scopes,
                    generation = excluded.generation,
                    expires_at = excluded.expires_at
                returning *
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, pending.state)
                statement.setString(2, pending.userId)
                statement.setString(3, pending.skillId)
                statement.setString(4, pending.provider)
                statement.setString(5, pending.requestedScopes.toScopesColumn())
                statement.setLong(6, pending.generation)
                statement.setInstant(7, pending.expiresAt)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.toPendingState()
                }
            }
        }

    override suspend fun consume(state: String, now: Instant): SkillOAuthPendingState? =
        dataSource.write { connection ->
            connection.prepareStatement(
                "delete from skill_oauth_pending_states where state = ? returning *"
            ).use { statement ->
                statement.setString(1, state)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) return@write null
                    val pending = resultSet.toPendingState()
                    if (pending.expiresAt.isBefore(now)) null else pending
                }
            }
        }

    private fun java.sql.ResultSet.toPendingState(): SkillOAuthPendingState =
        SkillOAuthPendingState(
            state = getString("state"),
            userId = getString("user_id"),
            skillId = getString("skill_id"),
            provider = getString("provider"),
            requestedScopes = getString("requested_scopes").fromScopesColumn(),
            generation = getLong("generation"),
            expiresAt = instant("expires_at"),
        )
}
