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
    ): SkillOAuthPendingState =
        dataSource.write { connection ->
            connection.prepareStatement(
                """
                insert into skill_oauth_requested_scopes(user_id, provider, requested_scopes, generation, updated_at)
                values (?, ?, '', 0, ?)
                on conflict (user_id, provider) do nothing
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, provider)
                statement.setInstant(3, now)
                statement.executeUpdate()
            }

            // Held until this transaction commits below — a concurrent call for the same
            // (userId, provider) blocks here until this whole method (merge, bump, AND the
            // pending-state write further down) has fully committed, not just the merge/bump
            // part. That's what actually closes the race: splitting these into two separate
            // transactions would leave a window where an older, paused call could still
            // unconditionally overwrite a newer call's already-written pending state.
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
                        resultSet.getString("requested_scopes").fromScopesColumn(),
                        resultSet.getLong("generation"),
                        resultSet.instant("updated_at"),
                    )
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
                statement.setString(1, mergedScopes.toScopesColumn())
                statement.setLong(2, nextGeneration)
                statement.setInstant(3, now)
                statement.setString(4, userId)
                statement.setString(5, provider)
                statement.executeUpdate()
            }

            // Safe to be unconditional (no generation guard needed here, unlike credentials'
            // upsert): the row lock above already serializes every call for this (userId,
            // provider) pair, so nothing else can be racing this specific write.
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
                statement.setString(5, mergedScopes.toScopesColumn())
                statement.setLong(6, nextGeneration)
                statement.setInstant(7, expiresAt)
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
