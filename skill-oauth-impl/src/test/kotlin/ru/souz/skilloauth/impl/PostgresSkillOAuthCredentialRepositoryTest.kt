package ru.souz.skilloauth.impl

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class PostgresSkillOAuthCredentialRepositoryTest {
    @Test
    fun `find returns null when no credential exists`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_credential_missing")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthCredentialRepository(dataSource)

            assertNull(repository.find(userId = "user-1", provider = "yandex"))
        }
    }

    @Test
    fun `upsert then find round-trips a credential`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_credential_roundtrip")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthCredentialRepository(dataSource)
            val now = Instant.parse("2026-01-01T00:00:00Z")

            repository.upsert(
                SkillOAuthCredential(
                    userId = "user-1",
                    provider = "yandex",
                    accessTokenEncrypted = "enc-access-1",
                    refreshTokenEncrypted = "enc-refresh-1",
                    grantedScopes = listOf("login:info", "login:email"),
                    expiresAt = now.plusSeconds(3600),
                    generation = 1,
                    createdAt = now,
                    updatedAt = now,
                )
            )

            val stored = repository.find(userId = "user-1", provider = "yandex")

            assertEquals("enc-access-1", stored?.accessTokenEncrypted)
            assertEquals("enc-refresh-1", stored?.refreshTokenEncrypted)
            assertEquals(listOf("login:info", "login:email"), stored?.grantedScopes)
            assertEquals(now.plusSeconds(3600), stored?.expiresAt)
        }
    }

    @Test
    fun `upsert replaces the previous credential for the same user and provider`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_credential_replace")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthCredentialRepository(dataSource)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val credential = SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = "enc-access-1",
                refreshTokenEncrypted = "enc-refresh-1",
                grantedScopes = listOf("login:info"),
                expiresAt = now,
                generation = 1,
                createdAt = now,
                updatedAt = now,
            )
            repository.upsert(credential)

            repository.upsert(credential.copy(accessTokenEncrypted = "enc-access-2", updatedAt = now.plusSeconds(60)))

            val stored = repository.find(userId = "user-1", provider = "yandex")
            assertEquals("enc-access-2", stored?.accessTokenEncrypted)
        }
    }

    @Test
    fun `upsert rejects a write whose generation is older than the stored credential's`() = runTest {
        // Regression test for the race where a callback whose own pending state was already
        // superseded by a fresher authorization (see SkillOAuthGatewayImpl.handleCallback) finishes its
        // token exchange *after* that fresher one already saved — its stale write must not clobber
        // the newer credential. Exercises the real `on conflict ... where` guard in Postgres, not
        // just the in-memory fake.
        val schema = newSkillOAuthTestSchema("skill_oauth_credential_stale")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthCredentialRepository(dataSource)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            repository.upsert(
                SkillOAuthCredential(
                    userId = "user-1",
                    provider = "yandex",
                    accessTokenEncrypted = "enc-fresh",
                    refreshTokenEncrypted = null,
                    grantedScopes = listOf("login:info", "iot:control"),
                    expiresAt = null,
                    generation = 2,
                    createdAt = now,
                    updatedAt = now,
                )
            )

            val result = repository.upsert(
                SkillOAuthCredential(
                    userId = "user-1",
                    provider = "yandex",
                    accessTokenEncrypted = "enc-stale",
                    refreshTokenEncrypted = null,
                    grantedScopes = listOf("iot:control"),
                    expiresAt = null,
                    generation = 1,
                    createdAt = now,
                    updatedAt = now.plusSeconds(60),
                )
            )

            assertNull(result)
            val stored = repository.find("user-1", "yandex")
            assertEquals("enc-fresh", stored?.accessTokenEncrypted)
            assertEquals(listOf("login:info", "iot:control"), stored?.grantedScopes)
        }
    }

    @Test
    fun `upsert accepts a write at the same generation, e g a routine token refresh`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_credential_same_gen")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthCredentialRepository(dataSource)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            repository.upsert(
                SkillOAuthCredential(
                    userId = "user-1",
                    provider = "yandex",
                    accessTokenEncrypted = "enc-original",
                    refreshTokenEncrypted = "enc-refresh",
                    grantedScopes = listOf("login:info"),
                    expiresAt = now,
                    generation = 1,
                    createdAt = now,
                    updatedAt = now,
                )
            )

            val result = repository.upsert(
                SkillOAuthCredential(
                    userId = "user-1",
                    provider = "yandex",
                    accessTokenEncrypted = "enc-refreshed",
                    refreshTokenEncrypted = "enc-refresh",
                    grantedScopes = listOf("login:info"),
                    expiresAt = now.plusSeconds(3600),
                    generation = 1,
                    createdAt = now,
                    updatedAt = now.plusSeconds(60),
                )
            )

            assertEquals("enc-refreshed", result?.accessTokenEncrypted)
        }
    }

    @Test
    fun `upsert rejects a same-generation write whose revision is stale`() = runTest {
        // Regression test for two token refreshes racing for the same (userId, provider):
        // both read the credential at the same generation, so `generation` alone can't order
        // them. The second writer's `revision` (still the pre-refresh value it read) must no
        // longer match the row's actual revision once the first writer already landed — its
        // write must be rejected outright, not silently clobber the first writer's tokens.
        val schema = newSkillOAuthTestSchema("skill_oauth_credential_revision_race")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthCredentialRepository(dataSource)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            val original = SkillOAuthCredential(
                userId = "user-1",
                provider = "yandex",
                accessTokenEncrypted = "enc-original",
                refreshTokenEncrypted = "enc-refresh",
                grantedScopes = listOf("login:info"),
                expiresAt = now,
                generation = 1,
                createdAt = now,
                updatedAt = now,
            )
            repository.upsert(original)
            val readByBothRefreshes = repository.find("user-1", "yandex")!!

            val firstRefreshWinner = repository.upsert(
                readByBothRefreshes.copy(accessTokenEncrypted = "enc-refreshed-first", updatedAt = now.plusSeconds(60))
            )
            val secondRefreshLoser = repository.upsert(
                readByBothRefreshes.copy(accessTokenEncrypted = "enc-refreshed-second", updatedAt = now.plusSeconds(60))
            )

            assertEquals("enc-refreshed-first", firstRefreshWinner?.accessTokenEncrypted)
            assertNull(secondRefreshLoser)
            val stored = repository.find("user-1", "yandex")
            assertEquals("enc-refreshed-first", stored?.accessTokenEncrypted)
        }
    }

    @Test
    fun `distinct providers for the same user are stored independently`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_credential_multi_provider")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthCredentialRepository(dataSource)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            repository.upsert(
                SkillOAuthCredential("user-1", "yandex", "enc-a", null, emptyList(), null, 1, 0, now, now)
            )
            repository.upsert(
                SkillOAuthCredential("user-1", "github", "enc-b", null, emptyList(), null, 1, 0, now, now)
            )

            assertEquals("enc-a", repository.find("user-1", "yandex")?.accessTokenEncrypted)
            assertEquals("enc-b", repository.find("user-1", "github")?.accessTokenEncrypted)
        }
    }

    @Test
    fun `delete removes the credential`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_credential_delete")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthCredentialRepository(dataSource)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            repository.upsert(
                SkillOAuthCredential("user-1", "yandex", "enc-a", null, emptyList(), null, 1, 0, now, now)
            )

            repository.delete("user-1", "yandex")

            assertNull(repository.find("user-1", "yandex"))
        }
    }
}
