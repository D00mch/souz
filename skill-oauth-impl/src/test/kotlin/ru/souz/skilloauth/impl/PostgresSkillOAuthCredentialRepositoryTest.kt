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
    fun `distinct providers for the same user are stored independently`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_credential_multi_provider")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthCredentialRepository(dataSource)
            val now = Instant.parse("2026-01-01T00:00:00Z")
            repository.upsert(
                SkillOAuthCredential("user-1", "yandex", "enc-a", null, emptyList(), null, now, now)
            )
            repository.upsert(
                SkillOAuthCredential("user-1", "github", "enc-b", null, emptyList(), null, now, now)
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
                SkillOAuthCredential("user-1", "yandex", "enc-a", null, emptyList(), null, now, now)
            )

            repository.delete("user-1", "yandex")

            assertNull(repository.find("user-1", "yandex"))
        }
    }
}
