package ru.souz.backend.vk

import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresVkBotBindingRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig

class PostgresVkBotBindingRepositoryTest {
    @Test
    fun `postgres repository satisfies the VK binding contract`() = runTest {
        val schema = newPostgresSchema("postgres_vk_binding")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            val repository = PostgresVkBotBindingRepository(it)
            val checks = listOf<Pair<String, suspend (VkBotBindingRepository) -> Unit>>(
                "chat-scoped upsert replaces token state" to ::assertChatScopedUpsertContract,
                "unique token hash is enforced" to ::assertUniqueTokenHashContract,
                "listEnabled excludes disabled bindings" to ::assertEnabledListingContract,
                "last ts is persisted" to ::assertLastTsContract,
                "last ts applies only for the current lease owner, without monotonicity" to
                    ::assertLeaseScopedLastTsContract,
                "errors are stored and can disable the binding" to ::assertMarkErrorContract,
                "clearError removes stored error state" to ::assertClearErrorContract,
                "vk link metadata is persisted" to ::assertClaimVkUserContract,
                "lease allows one owner at a time" to ::assertLeaseContract,
            )
            checks.forEach { (description, check) ->
                try {
                    check(repository)
                } catch (e: Throwable) {
                    throw AssertionError("Contract check failed: $description", e)
                }
            }
        }
    }
}
