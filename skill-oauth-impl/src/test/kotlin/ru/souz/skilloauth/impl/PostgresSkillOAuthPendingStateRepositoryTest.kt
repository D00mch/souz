package ru.souz.skilloauth.impl

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    fun `consume refreshes the requested-scope row so an in-flight callback is not treated as abandoned`() = runTest {
        // D00mch's finding: without touching updated_at here, a callback whose own code exchange
        // happens to take a while right around the staleness cutoff could still be racing a
        // concurrent new authorization that reads the *original* beginAuthorization's stale
        // updated_at and wrongly treats this in-flight flow as abandoned, dropping its scopes
        // instead of widening on top of them.
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_consume_refresh")
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
            // The callback's own code exchange finishes near the end of the pending state's TTL —
            // consume() must touch updated_at at this point, not leave it at `now` above.
            val consumeAt = now.plusSeconds(550)
            repository.consume("state-first", consumeAt)

            // A second, unrelated authorization begins after the *original* beginAuthorization's
            // updated_at would already be stale, but not after the refreshed one.
            val muchLater = now.plusSeconds(700)
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

            assertEquals(setOf("login:info", "iot:control"), result.requestedScopes.toSet())
        }
    }

    @Test
    fun `beginAuthorization reuses a still-live pending state that already covers the requested scopes`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_reuse")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)
            val first = repository.beginAuthorization(
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
                scopes = listOf("login:info"),
                now = now,
                activeSince = activeSince,
                expiresAt = now.plusSeconds(600),
            )

            // Handed back completely unchanged — same state and generation, not "state-second".
            assertEquals(first.state, second.state)
            assertEquals(first.generation, second.generation)
            // still consumable under its original state — reuse must not have superseded it.
            assertEquals(first.state, repository.consume(first.state, now)?.state)
        }
    }

    @Test
    fun `beginAuthorization does not reuse an expired pending state`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_reuse_expired")
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
                expiresAt = now.plusSeconds(60),
            )
            val afterExpiry = now.plusSeconds(120)

            val second = repository.beginAuthorization(
                state = "state-second",
                userId = "user-1",
                skillId = "skill-2",
                provider = "yandex",
                scopes = listOf("login:info"),
                now = afterExpiry,
                activeSince = afterExpiry.minusSeconds(600),
                expiresAt = afterExpiry.plusSeconds(600),
            )

            assertEquals("state-second", second.state)
        }
    }

    @Test
    fun `beginAuthorization with reuseExisting=false always mints a fresh state`() = runTest {
        // Mirrors the gateway's forceReconnect path: force must bypass reuse even when a live,
        // fully-covering pending state exists.
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_reuse_force")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)
            val first = repository.beginAuthorization(
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
                skillId = "skill-1",
                provider = "yandex",
                scopes = listOf("login:info"),
                now = now,
                activeSince = activeSince,
                expiresAt = now.plusSeconds(600),
                reuseExisting = false,
            )

            assertEquals("state-second", second.state)
            // the old one must be superseded, not left dangling alongside the new one.
            assertNull(repository.consume(first.state, now))
        }
    }

    @Test
    fun `concurrent beginAuthorization calls for the same user and provider converge on one pending state`() = runTest {
        // Proves reuse is actually atomic with creation, not just correct when called sequentially
        // (D00mch's own critique of the sequential version of this test): without the reuse check
        // living inside the same row lock as the write, N truly concurrent callers could each see
        // "nothing to reuse" and race to create their own state, each invalidating the last.
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_reuse_concurrent")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)
            val concurrency = 16

            val results = coroutineScope {
                (0 until concurrency).map { i ->
                    async(Dispatchers.IO) {
                        repository.beginAuthorization(
                            state = "state-$i",
                            userId = "user-1",
                            skillId = "skill-1",
                            provider = "yandex",
                            scopes = listOf("login:info"),
                            now = now,
                            activeSince = activeSince,
                            expiresAt = now.plusSeconds(600),
                        )
                    }
                }.map { it.await() }
            }

            val distinctStates = results.map { it.state }.toSet()
            assertEquals(1, distinctStates.size, "expected every concurrent call to converge on one state, got: $distinctStates")
            // that one surviving state must still be consumable — none of the concurrent writers
            // left it half-superseded.
            assertEquals(distinctStates.single(), repository.consume(distinctStates.single(), now)?.state)
        }
    }

    @Test
    fun `concurrent beginAuthorization and consume for the same user and provider do not deadlock`() = runTest {
        // Stress-reproduces D00mch's lock-order finding: beginAuthorization used to lock
        // requested_scopes then pending_states, while consume locked pending_states then
        // requested_scopes — a concurrent pair racing for the same (userId, provider) could hit
        // Postgres deadlock detection (SQLSTATE 40P01), which DataSource.write does not retry. A
        // single consistent lock order makes that cycle structurally impossible; this test would
        // reliably surface a PSQLException here if the order regressed.
        val schema = newSkillOAuthTestSchema("skill_oauth_pending_lock_order")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthPendingStateRepository(dataSource)
            val rounds = 25

            coroutineScope {
                for (round in 0 until rounds) {
                    val begun = repository.beginAuthorization(
                        state = "state-$round",
                        userId = "user-1",
                        skillId = "skill-1",
                        provider = "yandex",
                        scopes = listOf("scope-$round"),
                        now = now,
                        activeSince = activeSince,
                        expiresAt = now.plusSeconds(600),
                        reuseExisting = false,
                    )
                    val beginJob = async(Dispatchers.IO) {
                        repository.beginAuthorization(
                            state = "state-widen-$round",
                            userId = "user-1",
                            skillId = "skill-1",
                            provider = "yandex",
                            scopes = listOf("other-scope-$round"),
                            now = now,
                            activeSince = activeSince,
                            expiresAt = now.plusSeconds(600),
                            reuseExisting = false,
                        )
                    }
                    val consumeJob = async(Dispatchers.IO) {
                        repository.consume(begun.state, now)
                    }
                    awaitAll(beginJob, consumeJob)
                }
            }
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
