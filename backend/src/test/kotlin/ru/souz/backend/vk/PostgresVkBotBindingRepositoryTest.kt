package ru.souz.backend.vk

import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresVkBotBindingRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig

class PostgresVkBotBindingRepositoryTest {
    @Test
    fun `postgres repository keeps one binding per chat and replaces token state`() = runTest {
        val schema = newPostgresSchema("postgres_vk_binding_chat")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            assertChatScopedUpsertContract(PostgresVkBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository enforces unique token hash`() = runTest {
        val schema = newPostgresSchema("postgres_vk_binding_token")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            assertUniqueTokenHashContract(PostgresVkBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository listEnabled excludes disabled bindings`() = runTest {
        val schema = newPostgresSchema("postgres_vk_binding_enabled")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            assertEnabledListingContract(PostgresVkBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository persists last ts`() = runTest {
        val schema = newPostgresSchema("postgres_vk_binding_update")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            assertLastTsContract(PostgresVkBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository applies last ts only for the current lease owner, without monotonicity`() = runTest {
        val schema = newPostgresSchema("postgres_vk_binding_update_owner")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            assertLeaseScopedLastTsContract(PostgresVkBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository stores errors and can disable binding`() = runTest {
        val schema = newPostgresSchema("postgres_vk_binding_error")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            assertMarkErrorContract(PostgresVkBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository clearError removes stored error state`() = runTest {
        val schema = newPostgresSchema("postgres_vk_binding_clear")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            assertClearErrorContract(PostgresVkBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository persists vk link metadata`() = runTest {
        val schema = newPostgresSchema("postgres_vk_binding_link")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            assertClaimVkUserContract(PostgresVkBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository lease allows one owner at a time`() = runTest {
        val schema = newPostgresSchema("postgres_vk_binding_lease")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres)

        dataSource.use {
            assertLeaseContract(PostgresVkBotBindingRepository(it))
        }
    }
}
