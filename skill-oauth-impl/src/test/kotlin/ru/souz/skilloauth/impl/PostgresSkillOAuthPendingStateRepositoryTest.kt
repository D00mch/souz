package ru.souz.skilloauth.impl

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class PostgresSkillOAuthPendingStateRepositoryTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val activeSince = now.minusSeconds(600)

    @Test
    fun `consume returns and deletes a pending state that has not expired`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_consume")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)
            repository.beginAuthorization(
                state = "state-1",
                userId = "user-1",
                skillId = "skill-1",
                provider = "yandex",
                scopes = listOf("login:info"),
                now = now,
                activeSince = activeSince,
                expiresAt = now.plusSeconds(600),
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
            repository.beginAuthorization(
                state = "state-2",
                userId = "user-1",
                skillId = "skill-1",
                provider = "yandex",
                scopes = emptyList(),
                now = createdAt,
                activeSince = createdAt.minusSeconds(600),
                expiresAt = createdAt.plusSeconds(60),
            )
            val afterExpiry = createdAt.plusSeconds(120)

            assertNull(repository.consume("state-2", afterExpiry))
            // expired row must be gone even though it was rejected, so it cannot be replayed later.
            assertNull(repository.consume("state-2", createdAt))
        }
    }

    @Test
    fun `beginAuthorization invalidates the old state and unions scopes for the same user and provider`() = runTest {
        // Exercises the real unique index + `on conflict (user_id, provider) do update` in
        // V1__skill_oauth.sql, and the atomic requested-scope merge — an in-memory fake can't catch
        // a mistake in that raw SQL.
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_upsert")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)

            repository.beginAuthorization(
                state = "state-first",
                userId = "user-1",
                skillId = "skill-1",
                provider = "yandex",
                scopes = listOf("login:info"),
                now = now,
                activeSince = activeSince,
                expiresAt = now.plusSeconds(600),
            )
            val second = repository.beginAuthorization(
                state = "state-second",
                userId = "user-1",
                skillId = "skill-2",
                provider = "yandex",
                scopes = listOf("iot:control"),
                now = now,
                activeSince = activeSince,
                expiresAt = now.plusSeconds(600),
            )

            assertEquals(setOf("login:info", "iot:control"), second.requestedScopes.toSet())
            assertEquals(2L, second.generation)
            // the old state must no longer be usable — superseded, not just left dangling.
            assertNull(repository.consume("state-first", now))
            assertEquals("state-second", repository.consume("state-second", now)?.state)
        }
    }

    @Test
    fun `beginAuthorization drops scopes from a requested-scope row abandoned before activeSince`() = runTest {
        // D00mch's finding, now against the combined atomic method: a request that's just sitting
        // there because its own authorize link expired and was never completed must not get
        // silently folded into a much later, unrelated authorization.
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_stale")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)

            repository.beginAuthorization(
                state = "state-first",
                userId = "user-1",
                skillId = "skill-1",
                provider = "yandex",
                scopes = listOf("login:info"),
                now = now,
                activeSince = activeSince,
                expiresAt = now.plusSeconds(600),
            )
            val muchLater = now.plusSeconds(1200)
            val result = repository.beginAuthorization(
                state = "state-second",
                userId = "user-1",
                skillId = "skill-2",
                provider = "yandex",
                scopes = listOf("iot:control"),
                now = muchLater,
                activeSince = muchLater.minusSeconds(600),
                expiresAt = muchLater.plusSeconds(600),
            )

            assertEquals(listOf("iot:control"), result.requestedScopes)
            // generation still strictly increases even though the old scopes were dropped.
            assertEquals(2L, result.generation)
        }
    }

    @Test
    fun `distinct providers for the same user are tracked independently`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_multi_provider")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)

            repository.beginAuthorization(
                state = "state-yandex",
                userId = "user-1",
                skillId = "skill-1",
                provider = "yandex",
                scopes = listOf("login:info"),
                now = now,
                activeSince = activeSince,
                expiresAt = now.plusSeconds(600),
            )
            val github = repository.beginAuthorization(
                state = "state-github",
                userId = "user-1",
                skillId = "skill-2",
                provider = "github",
                scopes = listOf("repo:read"),
                now = now,
                activeSince = activeSince,
                expiresAt = now.plusSeconds(600),
            )

            assertEquals(listOf("repo:read"), github.requestedScopes)
            assertEquals(1L, github.generation)
            // both must still be independently consumable.
            assertEquals("state-yandex", repository.consume("state-yandex", now)?.state)
            assertEquals("state-github", repository.consume("state-github", now)?.state)
        }
    }
}
