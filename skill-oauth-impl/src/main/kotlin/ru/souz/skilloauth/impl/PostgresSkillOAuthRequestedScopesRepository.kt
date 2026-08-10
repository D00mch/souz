package ru.souz.skilloauth.impl

import java.time.Instant
import javax.sql.DataSource

class PostgresSkillOAuthRequestedScopesRepository(
    private val dataSource: DataSource,
) : SkillOAuthRequestedScopesRepository {
    override suspend fun mergeAndBump(
        userId: String,
        provider: String,
        scopes: List<String>,
        now: Instant,
        activeSince: Instant,
    ): SkillOAuthRequestedScopesState =
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

            // A row untouched since before activeSince belongs to a long-abandoned request (its
            // own authorize link has long since expired) — its scopes are dropped rather than
            // folded into this new, unrelated authorization. generation still keeps increasing
            // either way, since it must remain a strictly monotonic ordering signal.
            val baseScopes = if (updatedAt.isBefore(activeSince)) emptyList() else existingScopes
            val merged = (baseScopes + scopes).distinct()
            val nextGeneration = existingGeneration + 1

            connection.prepareStatement(
                """
                update skill_oauth_requested_scopes
                set requested_scopes = ?, generation = ?, updated_at = ?
                where user_id = ? and provider = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, merged.toScopesColumn())
                statement.setLong(2, nextGeneration)
                statement.setInstant(3, now)
                statement.setString(4, userId)
                statement.setString(5, provider)
                statement.executeUpdate()
            }

            SkillOAuthRequestedScopesState(requestedScopes = merged, generation = nextGeneration)
        }
}
