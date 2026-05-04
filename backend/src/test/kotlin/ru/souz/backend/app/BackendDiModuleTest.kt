package ru.souz.backend.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import com.zaxxer.hikari.HikariDataSource
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.llm.LlmClientFactory
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.storage.StorageMode
import ru.souz.backend.storage.filesystem.FilesystemAgentEventRepository
import ru.souz.backend.storage.filesystem.FilesystemAgentExecutionRepository
import ru.souz.backend.storage.filesystem.FilesystemAgentStateRepository
import ru.souz.backend.storage.filesystem.FilesystemChatRepository
import ru.souz.backend.storage.filesystem.FilesystemOptionRepository
import ru.souz.backend.storage.filesystem.FilesystemMessageRepository
import ru.souz.backend.storage.filesystem.FilesystemTelegramBotBindingRepository
import ru.souz.backend.storage.filesystem.FilesystemUserRepository
import ru.souz.backend.storage.filesystem.FilesystemUserProviderKeyRepository
import ru.souz.backend.storage.filesystem.FilesystemUserSettingsRepository
import ru.souz.backend.storage.memory.MemoryAgentEventRepository
import ru.souz.backend.storage.memory.MemoryTelegramBotBindingRepository
import ru.souz.backend.storage.memory.MemoryUserRepository
import ru.souz.backend.storage.memory.MemoryUserProviderKeyRepository
import ru.souz.backend.storage.postgres.PostgresAgentEventRepository
import ru.souz.backend.storage.postgres.PostgresAgentExecutionRepository
import ru.souz.backend.storage.postgres.PostgresAgentStateRepository
import ru.souz.backend.storage.postgres.PostgresChatRepository
import ru.souz.backend.storage.postgres.PostgresMessageRepository
import ru.souz.backend.storage.postgres.PostgresOptionRepository
import ru.souz.backend.storage.postgres.PostgresTelegramBotBindingRepository
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.PostgresUserProviderKeyRepository
import ru.souz.backend.storage.postgres.PostgresUserSettingsRepository
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.user.repository.UserRepository

class BackendDiModuleTest {
    @Test
    fun `filesystem mode binds filesystem repositories`() {
        val appConfig = BackendAppConfig(
            featureFlags = BackendFeatureFlags(),
            storageMode = StorageMode.FILESYSTEM,
            proxyToken = null,
            dataDir = Files.createTempDirectory("backend-di-filesystem"),
            masterKey = "test-master-key",
        )
        val di = DI {
            import(
                backendDiModule(
                    systemPrompt = "test-system-prompt",
                    appConfig = appConfig,
                )
            )
        }

        assertIs<FilesystemUserRepository>(di.direct.instance<UserRepository>())
        assertIs<FilesystemChatRepository>(di.direct.instance<ChatRepository>())
        assertIs<FilesystemMessageRepository>(di.direct.instance<MessageRepository>())
        assertIs<FilesystemAgentStateRepository>(di.direct.instance<AgentStateRepository>())
        assertIs<FilesystemAgentExecutionRepository>(di.direct.instance<AgentExecutionRepository>())
        assertIs<FilesystemOptionRepository>(di.direct.instance<OptionRepository>())
        assertIs<FilesystemAgentEventRepository>(di.direct.instance<AgentEventRepository>())
        assertIs<FilesystemUserSettingsRepository>(di.direct.instance<UserSettingsRepository>())
        assertIs<FilesystemUserProviderKeyRepository>(di.direct.instance<UserProviderKeyRepository>())
        assertIs<FilesystemTelegramBotBindingRepository>(di.direct.instance<TelegramBotBindingRepository>())
        assertIs<UserProviderKeyService>(di.direct.instance<UserProviderKeyService>())
        assertIs<ExecutionQuotaManager>(di.direct.instance<ExecutionQuotaManager>())
        assertIs<LlmClientFactory>(di.direct.instance<LlmClientFactory>())
    }

    @Test
    fun `postgres mode binds postgres repositories when durable replay is enabled`() {
        val appConfig = postgresAppConfig(
            schema = newPostgresSchema("backend_di_durable"),
            durableEventReplay = true,
        )
        val di = DI {
            import(
                backendDiModule(
                    systemPrompt = "test-system-prompt",
                    appConfig = appConfig,
                )
            )
        }
        val dataSource = di.direct.instance<HikariDataSource>()

        try {
            assertIs<PostgresUserRepository>(di.direct.instance<UserRepository>())
            assertIs<PostgresChatRepository>(di.direct.instance<ChatRepository>())
            assertIs<PostgresMessageRepository>(di.direct.instance<MessageRepository>())
            assertIs<PostgresAgentStateRepository>(di.direct.instance<AgentStateRepository>())
            assertIs<PostgresAgentExecutionRepository>(di.direct.instance<AgentExecutionRepository>())
            assertIs<PostgresOptionRepository>(di.direct.instance<OptionRepository>())
            assertIs<PostgresAgentEventRepository>(di.direct.instance<AgentEventRepository>())
            assertIs<PostgresUserSettingsRepository>(di.direct.instance<UserSettingsRepository>())
            assertIs<PostgresUserProviderKeyRepository>(di.direct.instance<UserProviderKeyRepository>())
            assertIs<PostgresTelegramBotBindingRepository>(di.direct.instance<TelegramBotBindingRepository>())
            assertIs<UserProviderKeyService>(di.direct.instance<UserProviderKeyService>())
            assertIs<ExecutionQuotaManager>(di.direct.instance<ExecutionQuotaManager>())
            assertIs<LlmClientFactory>(di.direct.instance<LlmClientFactory>())
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `postgres mode keeps live event flow in memory when durable replay is disabled`() {
        val appConfig = postgresAppConfig(
            schema = newPostgresSchema("backend_di_live"),
            durableEventReplay = false,
        )
        val di = DI {
            import(
                backendDiModule(
                    systemPrompt = "test-system-prompt",
                    appConfig = appConfig,
                )
            )
        }
        val dataSource = di.direct.instance<HikariDataSource>()

        try {
            assertIs<PostgresUserRepository>(di.direct.instance<UserRepository>())
            assertIs<PostgresChatRepository>(di.direct.instance<ChatRepository>())
            assertIs<PostgresMessageRepository>(di.direct.instance<MessageRepository>())
            assertIs<PostgresAgentStateRepository>(di.direct.instance<AgentStateRepository>())
            assertIs<PostgresAgentExecutionRepository>(di.direct.instance<AgentExecutionRepository>())
            assertIs<PostgresOptionRepository>(di.direct.instance<OptionRepository>())
            assertIs<MemoryAgentEventRepository>(di.direct.instance<AgentEventRepository>())
            assertIs<PostgresUserSettingsRepository>(di.direct.instance<UserSettingsRepository>())
            assertIs<PostgresUserProviderKeyRepository>(di.direct.instance<UserProviderKeyRepository>())
            assertIs<PostgresTelegramBotBindingRepository>(di.direct.instance<TelegramBotBindingRepository>())
            assertIs<UserProviderKeyService>(di.direct.instance<UserProviderKeyService>())
            assertIs<ExecutionQuotaManager>(di.direct.instance<ExecutionQuotaManager>())
            assertIs<LlmClientFactory>(di.direct.instance<LlmClientFactory>())
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `memory mode binds in memory provider key repository and quota services`() {
        val appConfig = BackendAppConfig(
            featureFlags = BackendFeatureFlags(),
            storageMode = StorageMode.MEMORY,
            proxyToken = null,
            dataDir = Files.createTempDirectory("backend-di-memory"),
            masterKey = "test-master-key",
        )
        val di = DI {
            import(
                backendDiModule(
                    systemPrompt = "test-system-prompt",
                    appConfig = appConfig,
                )
            )
        }

        assertIs<MemoryUserRepository>(di.direct.instance<UserRepository>())
        assertIs<MemoryUserProviderKeyRepository>(di.direct.instance<UserProviderKeyRepository>())
        assertIs<MemoryTelegramBotBindingRepository>(di.direct.instance<TelegramBotBindingRepository>())
        assertIs<UserProviderKeyService>(di.direct.instance<UserProviderKeyService>())
        assertIs<ExecutionQuotaManager>(di.direct.instance<ExecutionQuotaManager>())
        assertIs<LlmClientFactory>(di.direct.instance<LlmClientFactory>())
    }
}
