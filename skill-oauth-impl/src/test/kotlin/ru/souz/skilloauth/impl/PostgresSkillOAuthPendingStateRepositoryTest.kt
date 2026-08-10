package ru.souz.skilloauth.impl

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class PostgresSkillOAuthPendingStateRepositoryTest {
    @Test
    fun `consume returns and deletes a pending state that has not expired`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_consume")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            repository.upsertSupersedingByUserAndProvider(
                SkillOAuthPendingState(
                    state = "state-1",
                    userId = "user-1",
                    skillId = "skill-1",
                    provider = "yandex",
                    requestedScopes = listOf("login:info"),
                    generation = 1,
                    expiresAt = now.plusSeconds(600),
                )
            )

            val consumed = repository.consume("state-1", now)

            assertEquals("user-1", consumed?.userId)
            assertEquals("skill-1", consumed?.skillId)
            assertEquals(listOf("login:info"), consumed?.requestedScopes)
            assertEquals(1L, consumed?.generation)
            // single-use: a second consume must find nothing, since the row was deleted.
            assertNull(repository.consume("state-1", now))
        }
    }

    @Test
    fun `consume returns null for an unknown state`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_unknown")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)

            assertNull(repository.consume("does-not-exist", Instant.now()))
        }
    }

    @Test
    fun `consume returns null and still deletes an expired state`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_expired")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)
            val createdAt = Instant.parse("2026-01-01T00:00:00Z")
            repository.upsertSupersedingByUserAndProvider(
                SkillOAuthPendingState(
                    state = "state-2",
                    userId = "user-1",
                    skillId = "skill-1",
                    provider = "yandex",
                    requestedScopes = emptyList(),
                    generation = 1,
                    expiresAt = createdAt.plusSeconds(60),
                )
            )
            val afterExpiry = createdAt.plusSeconds(120)

            assertNull(repository.consume("state-2", afterExpiry))
            // expired row must be gone even though it was rejected, so it cannot be replayed later.
            assertNull(repository.consume("state-2", createdAt))
        }
    }

    @Test
    fun `upsertSupersedingByUserAndProvider invalidates the old state for the same user and provider`() = runTest {
        // Exercises the real unique index + `on conflict (user_id, provider) do update` in
        // V1__skill_oauth.sql — an in-memory fake can't catch a mistake in that raw SQL. Scope
        // merging itself now happens in SkillOAuthRequestedScopesRepository, not here — this
        // repository just needs to guarantee at most one live state per (user_id, provider).
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_upsert")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)
            val now = Instant.parse("2026-01-01T00:00:00Z")

            repository.upsertSupersedingByUserAndProvider(
                SkillOAuthPendingState(
                    state = "state-first",
                    userId = "user-1",
                    skillId = "skill-1",
                    provider = "yandex",
                    requestedScopes = listOf("login:info"),
                    generation = 1,
                    expiresAt = now.plusSeconds(600),
                )
            )
            val second = repository.upsertSupersedingByUserAndProvider(
                SkillOAuthPendingState(
                    state = "state-second",
                    userId = "user-1",
                    skillId = "skill-2",
                    provider = "yandex",
                    requestedScopes = listOf("login:info", "iot:control"),
                    generation = 2,
                    expiresAt = now.plusSeconds(600),
                )
            )

            assertEquals(setOf("login:info", "iot:control"), second.requestedScopes.toSet())
            assertEquals(2L, second.generation)
            // the old state must no longer be usable — superseded, not just left dangling.
            assertNull(repository.consume("state-first", now))
            assertEquals("state-second", repository.consume("state-second", now)?.state)
        }
    }
}
