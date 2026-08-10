package ru.souz.skilloauth.impl

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class PostgresSkillOAuthRequestedScopesRepositoryTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val activeSince = now.minusSeconds(600)

    @Test
    fun `mergeAndBump creates a fresh row starting at generation 1`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_reqscopes_fresh")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthRequestedScopesRepository(dataSource)

            val result = repository.mergeAndBump("user-1", "yandex", listOf("login:info"), now, activeSince)

            assertEquals(listOf("login:info"), result.requestedScopes)
            assertEquals(1L, result.generation)
        }
    }

    @Test
    fun `mergeAndBump unions with the previous call and bumps generation`() = runTest {
        // The core property SkillOAuthApiImpl.startAuthorization relies on: a second call for the
        // same (userId, provider) sees and widens on top of the first one's request, even though
        // nothing has been saved as a credential or pending state yet.
        val schema = newSkillOAuthTestSchema("skill_oauth_reqscopes_union")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthRequestedScopesRepository(dataSource)

            repository.mergeAndBump("user-1", "yandex", listOf("login:info"), now, activeSince)
            val second = repository.mergeAndBump("user-1", "yandex", listOf("iot:control"), now, activeSince)

            assertEquals(setOf("login:info", "iot:control"), second.requestedScopes.toSet())
            assertEquals(2L, second.generation)
        }
    }

    @Test
    fun `mergeAndBump does not duplicate a scope requested twice`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_reqscopes_dedup")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthRequestedScopesRepository(dataSource)

            repository.mergeAndBump("user-1", "yandex", listOf("login:info"), now, activeSince)
            val second = repository.mergeAndBump("user-1", "yandex", listOf("login:info"), now, activeSince)

            assertEquals(listOf("login:info"), second.requestedScopes)
            assertEquals(2L, second.generation)
        }
    }

    @Test
    fun `distinct providers for the same user are tracked independently`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_reqscopes_multi")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthRequestedScopesRepository(dataSource)

            repository.mergeAndBump("user-1", "yandex", listOf("login:info"), now, activeSince)
            val github = repository.mergeAndBump("user-1", "github", listOf("repo:read"), now, activeSince)

            assertEquals(listOf("repo:read"), github.requestedScopes)
            assertEquals(1L, github.generation)
        }
    }

    @Test
    fun `mergeAndBump drops scopes from a row abandoned before activeSince`() = runTest {
        // D00mch's original point applied to this table: a request that's just sitting there
        // because its own authorize link expired and was never completed must not get silently
        // folded into a much later, unrelated authorization.
        val schema = newSkillOAuthTestSchema("skill_oauth_reqscopes_stale")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthRequestedScopesRepository(dataSource)

            repository.mergeAndBump("user-1", "yandex", listOf("login:info"), now, activeSince)
            val muchLater = now.plusSeconds(1200)
            val result = repository.mergeAndBump(
                "user-1", "yandex", listOf("iot:control"), muchLater, muchLater.minusSeconds(600),
            )

            assertEquals(listOf("iot:control"), result.requestedScopes)
            // generation still strictly increases even though the old scopes were dropped.
            assertEquals(2L, result.generation)
        }
    }

    @Test
    fun `mergeAndBump keeps scopes from a row still active as of activeSince`() = runTest {
        val schema = newSkillOAuthTestSchema("skill_oauth_reqscopes_active")
        skillOAuthTestDataSource(schema).use { dataSource ->
            val repository = PostgresSkillOAuthRequestedScopesRepository(dataSource)

            repository.mergeAndBump("user-1", "yandex", listOf("login:info"), now, activeSince)
            val soonAfter = now.plusSeconds(60)
            val result = repository.mergeAndBump(
                "user-1", "yandex", listOf("iot:control"), soonAfter, soonAfter.minusSeconds(600),
            )

            assertEquals(setOf("login:info", "iot:control"), result.requestedScopes.toSet())
        }
    }
}
