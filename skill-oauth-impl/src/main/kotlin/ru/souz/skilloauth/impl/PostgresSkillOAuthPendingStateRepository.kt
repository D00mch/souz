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
            // (userId, provider) blocks here until this whole method (reuse-or-widen decision,
            // merge, bump, AND the pending-state write further down) has fully committed, not just
            // part of it. That's what actually closes the race: splitting these into separate
            // transactions would leave a window where two concurrent callers could each decide
            // independently to create their own pending state, or an older, paused call could
            // unconditionally overwrite a newer call's already-written one.
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

            // Still holding the row lock above: a still-live pending state that already covers
            // [scopes] is handed back completely unchanged (same state/expiresAt/generation) rather
            // than superseded — see the interface doc comment on why this decision has to happen
            // inside the same lock as any write, not as a separate read beforehand.
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
                    return@write existingPending
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
            // Locks the requested-scopes row for this pending state's (userId, provider) FIRST —
            // the same order beginAuthorization uses (that row, then the pending-states row) —
            // before this transaction touches skill_oauth_pending_states at all. Locking in the
            // reverse order, as this used to (delete the pending row, only then touch
            // requested-scopes), let a concurrent beginAuthorization/consume pair for the same
            // pair deadlock: one holding the requested-scopes lock while waiting on the pending-row
            // lock, the other holding the pending-row lock while waiting on requested-scopes —
            // Postgres detects the cycle and aborts one side with SQLSTATE 40P01, which
            // DataSource.write does not retry. A single consistent lock order makes that cycle
            // structurally impossible.
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
                    if (!resultSet.next()) return@write null
                    val found = resultSet.toPendingState()
                    if (found.expiresAt.isBefore(now)) null else found
                }
            } ?: return@write null

            // Refreshes the durable requested-scope tracking row's freshness the moment its flow
            // starts actually completing (this callback is about to exchange the code and save a
            // credential) — not just when a *new* authorization is begun. Without this, a callback
            // whose code exchange happens to take a while right around the staleness cutoff (see
            // beginAuthorization's `activeSince`) could still be racing a concurrent new
            // authorization that reads a stale `updated_at` and wrongly treats this in-flight flow
            // as abandoned, discarding its requested scopes instead of widening on top of them.
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
            requestedScopes = getString("requested_scopes").fromScopesColumn(),
            generation = getLong("generation"),
            expiresAt = instant("expires_at"),
        )
}
