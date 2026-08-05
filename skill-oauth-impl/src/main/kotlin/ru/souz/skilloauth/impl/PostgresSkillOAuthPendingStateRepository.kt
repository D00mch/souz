package ru.souz.skilloauth.impl

import java.time.Instant
import javax.sql.DataSource

class PostgresSkillOAuthPendingStateRepository(
    private val dataSource: DataSource,
) : SkillOAuthPendingStateRepository {
    override suspend fun create(pending: SkillOAuthPendingState) {
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                insert into skill_oauth_pending_states(
                    state, user_id, skill_id, provider, requested_scopes, expires_at
                )
                values (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, pending.state)
                statement.setString(2, pending.userId)
                statement.setString(3, pending.skillId)
                statement.setString(4, pending.provider)
                statement.setString(5, pending.requestedScopes.toScopesColumn())
                statement.setInstant(6, pending.expiresAt)
                statement.executeUpdate()
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

    override suspend fun consumeActiveForUserAndProvider(
        userId: String,
        provider: String,
        now: Instant,
    ): List<SkillOAuthPendingState> =
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                delete from skill_oauth_pending_states
                where user_id = ? and provider = ? and expires_at >= ?
                returning *
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, provider)
                statement.setInstant(3, now)
                statement.executeQuery().use { resultSet ->
                    val results = mutableListOf<SkillOAuthPendingState>()
                    while (resultSet.next()) {
                        results += resultSet.toPendingState()
                    }
                    results
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
            expiresAt = instant("expires_at"),
        )
}
