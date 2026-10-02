package ru.souz.skilloauth.impl

import java.time.Instant
import javax.sql.DataSource

class PostgresSkillOAuthPendingStateRepository(
    private val dataSource: DataSource,
) : SkillOAuthPendingStateRepository {
    override suspend fun beginAuthorization(
        state: String,
        userId: String,
        skillId: String,
        provider: String,
        scopes: List<String>,
        now: Instant,
        activeSince: Instant,
        expiresAt: Instant,
        reuseExisting: Boolean,
    ): SkillOAuthPendingState =
        dataSource.withTransaction { connection ->
            connection.prepareStatement(
                """
                insert into skill_oauth_requested_scopes(user_id, provider, requested_scopes, generation, updated_at)
                values (?, ?, '{}', 0, ?)
                on conflict (user_id, provider) do nothing
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, provider)
                statement.setInstant(3, now)
                statement.executeUpdate()
            }

            // Serialize reuse, scope merging, generation, and pending-state writes for this pair.
            val (existingScopes, existingGeneration, updatedAt) = connection.prepareStatement(
                """
                select requested_scopes, generation, updated_at from skill_oauth_requested_scopes
                where user_id = ? and provider = ?
                for update
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, provider)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    Triple(
                        resultSet.scopes("requested_scopes"),
                        resultSet.getLong("generation"),
                        resultSet.instant("updated_at"),
                    )
                }
            }

            // A covering live link keeps its original state, expiry, and generation.
            if (reuseExisting) {
                val existingPending = connection.prepareStatement(
                    "select * from skill_oauth_pending_states where user_id = ? and provider = ?"
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.setString(2, provider)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.toPendingState() else null
                    }
                }
                if (existingPending != null &&
                    !existingPending.expiresAt.isBefore(now) &&
                    scopes.all { it in existingPending.requestedScopes }
                ) {
                    return@withTransaction existingPending
                }
            }

            val baseScopes = if (updatedAt.isBefore(activeSince)) emptyList() else existingScopes
            val mergedScopes = (baseScopes + scopes).distinct()
            val nextGeneration = existingGeneration + 1

            connection.prepareStatement(
                """
                update skill_oauth_requested_scopes
                set requested_scopes = ?, generation = ?, updated_at = ?
                where user_id = ? and provider = ?
                """.trimIndent()
            ).use { statement ->
                statement.setScopes(1, mergedScopes)
                statement.setLong(2, nextGeneration)
                statement.setInstant(3, now)
                statement.setString(4, userId)
                statement.setString(5, provider)
                statement.executeUpdate()
            }

            // The requested-scope lock also serializes replacement of this pair's pending state.
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
                statement.setString(1, state)
                statement.setString(2, userId)
                statement.setString(3, skillId)
                statement.setString(4, provider)
                statement.setScopes(5, mergedScopes)
                statement.setLong(6, nextGeneration)
                statement.setInstant(7, expiresAt)
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.toPendingState()
                }
            }
        }

    override suspend fun consume(state: String, now: Instant): SkillOAuthPendingState? =
        dataSource.withTransaction { connection ->
            // Match beginAuthorization's lock order: requested scopes before pending state.
            connection.prepareStatement(
                """
                select rs.user_id
                from skill_oauth_pending_states ps
                join skill_oauth_requested_scopes rs
                    on rs.user_id = ps.user_id and rs.provider = ps.provider
                where ps.state = ?
                for update of rs
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, state)
                statement.executeQuery().use { it.next() }
            }

            val pending = connection.prepareStatement(
                "delete from skill_oauth_pending_states where state = ? returning *"
            ).use { statement ->
                statement.setString(1, state)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) return@withTransaction null
                    val found = resultSet.toPendingState()
                    if (found.expiresAt.isBefore(now)) null else found
                }
            } ?: return@withTransaction null

            // Keep this flow's scopes active while its callback exchanges the code.
            connection.prepareStatement(
                "update skill_oauth_requested_scopes set updated_at = ? where user_id = ? and provider = ?"
            ).use { statement ->
                statement.setInstant(1, now)
                statement.setString(2, pending.userId)
                statement.setString(3, pending.provider)
                statement.executeUpdate()
            }

            pending
        }

    private fun java.sql.ResultSet.toPendingState(): SkillOAuthPendingState =
        SkillOAuthPendingState(
            state = getString("state"),
            userId = getString("user_id"),
            skillId = getString("skill_id"),
            provider = getString("provider"),
            requestedScopes = scopes("requested_scopes"),
            generation = getLong("generation"),
            expiresAt = instant("expires_at"),
        )
}
