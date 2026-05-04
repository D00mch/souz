package ru.souz.backend.telegram

import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresTelegramBotBindingRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig

class PostgresTelegramBotBindingRepositoryTest {
    @Test
    fun `postgres repository keeps one binding per chat and replaces token state`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_chat")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)

        dataSource.use {
            assertChatScopedUpsertContract(PostgresTelegramBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository enforces unique token hash`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_token")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)

        dataSource.use {
            assertUniqueTokenHashContract(PostgresTelegramBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository listEnabled excludes disabled bindings`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_enabled")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)

        dataSource.use {
            assertEnabledListingContract(PostgresTelegramBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository persists last update id`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_update")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)

        dataSource.use {
            assertLastUpdateContract(PostgresTelegramBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository stores errors and can disable binding`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_error")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)

        dataSource.use {
            assertMarkErrorContract(PostgresTelegramBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository clearError removes stored error state`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_clear")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)

        dataSource.use {
            assertClearErrorContract(PostgresTelegramBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository persists telegram link metadata`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_link")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)

        dataSource.use {
            assertLinkTelegramUserContract(PostgresTelegramBotBindingRepository(it))
        }
    }

    @Test
    fun `postgres repository lease allows one owner at a time`() = runTest {
        val schema = newPostgresSchema("postgres_tg_binding_lease")
        val dataSource = PostgresDataSourceFactory.create(postgresAppConfig(schema).postgres!!)

        dataSource.use {
            assertLeaseContract(PostgresTelegramBotBindingRepository(it))
        }
    }
}
