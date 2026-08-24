package ru.souz.backend.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import com.zaxxer.hikari.HikariDataSource
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.instanceOrNull
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.SkillToolBindingTags
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.GigaHttpClientResource
import ru.souz.llms.giga.GigaAuth
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.settings.repository.BackendServerPreferenceStore
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.service.BackendSettingsProvider
import ru.souz.backend.storage.postgres.PostgresAgentEventRepository
import ru.souz.backend.storage.postgres.PostgresAgentExecutionRepository
import ru.souz.backend.storage.postgres.PostgresAgentStateRepository
import ru.souz.backend.storage.postgres.PostgresBackendServerPreferenceStore
import ru.souz.backend.storage.postgres.PostgresChatRepository
import ru.souz.backend.storage.postgres.PostgresMessageRepository
import ru.souz.backend.storage.postgres.PostgresOptionRepository
import ru.souz.backend.storage.postgres.PostgresTelegramBotBindingRepository
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.PostgresUserProviderKeyRepository
import ru.souz.backend.storage.postgres.PostgresUserSettingsRepository
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotBindingService
import ru.souz.backend.user.repository.UserRepository
import ru.souz.llms.LLMToolSetup
import ru.souz.skills.registry.FileSystemSkillRegistryRepository
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.SkillCommandExecutor

class BackendDiModuleTest {
    @Test
    fun `backend binds only postgres repositories`() {
        val appConfig = testAppConfig()
        val dataSource = HikariDataSource()
        val di = testDi(appConfig, dataSource)

        try {
            assertIs<HikariDataSource>(di.direct.instance<HikariDataSource>())
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
            assertIs<PostgresBackendServerPreferenceStore>(di.direct.instance<BackendServerPreferenceStore>())
            assertIs<BackendSettingsProvider>(di.direct.instance<ru.souz.db.SettingsProvider>())
            assertIs<UserProviderKeyService>(di.direct.instance<UserProviderKeyService>())
            assertIs<ExecutionQuotaManager>(di.direct.instance<ExecutionQuotaManager>())
            assertIs<ProviderHttpClients>(di.direct.instance<ProviderHttpClients>())
            assertNull(di.direct.instanceOrNull<GigaHttpClientResource>())
            assertNull(di.direct.instanceOrNull<GigaAuth>())
            assertNull(di.direct.instanceOrNull<GigaRestChatAPI>())
            assertIs<FileSystemSkillRegistryRepository>(di.direct.instance<SkillRegistryRepository>())

            val httpDependencies = di.direct.instance<BackendHttpDependencies>()
            assertSame(httpDependencies, di.direct.instance<BackendHttpDependencies>())
            assertSame(di.direct.instance<BackendFeatureFlags>(), httpDependencies.featureFlags)
            assertEquals("test-proxy-token", httpDependencies.trustedProxyToken())
            val configuredModel = di.direct.instance<ru.souz.db.SettingsProvider>().gigaModel
            val expectedHealthModel = configuredModel
                .takeIf { it in BackendLlmSupport.chatModels }
                ?: BackendLlmSupport.fallbackChatModel
            assertEquals(expectedHealthModel.alias, httpDependencies.selectedModel())
            assertNotNull(httpDependencies.onboardingService)
            assertNotNull(httpDependencies.userSettingsService)
            assertNotNull(httpDependencies.providerKeyService)
            assertNotNull(httpDependencies.chatService)
            assertNotNull(httpDependencies.messageService)
            assertNotNull(httpDependencies.executionService)
            assertNotNull(httpDependencies.optionService)
            assertNotNull(httpDependencies.eventService)
            assertNull(httpDependencies.telegramBotBindingService)
        } finally {
            di.direct.instance<BackendRuntimeResources>().close()
        }
    }

    @Test
    fun `backend DI resolves without the skill OAuth token key configured`() {
        // Regression test: a real deployment crash-looped because BackendHttpDependencies is
        // resolved eagerly at startup and used to throw via `?: error(...)` when Yandex OAuth
        // config was absent, taking down the whole backend over an unrelated, unconfigured,
        // non-flag-gated feature. Skill OAuth must degrade to "disabled" instead.
        val appConfig = testAppConfig(includeSkillOAuthConfig = false)
        val dataSource = HikariDataSource()
        val di = testDi(appConfig, dataSource)

        try {
            val httpDependencies = di.direct.instance<BackendHttpDependencies>()

            assertNull(httpDependencies.skillOAuthGatewayImpl)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `backend DI resolves with the skill OAuth token key but no fully configured provider`() {
        // A key with zero usable providers is just as inert as no key at all — SkillOAuthGateway
        // must not come up promising a connection that can never succeed.
        val appConfig = testAppConfig().copy(skillOAuthProviderCredentials = emptyMap())
        val dataSource = HikariDataSource()
        val di = testDi(appConfig, dataSource)

        try {
            val httpDependencies = di.direct.instance<BackendHttpDependencies>()

            assertNull(httpDependencies.skillOAuthGatewayImpl)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `http dependencies include telegram binding when feature is enabled`() {
        val appConfig = testAppConfig(
            featureFlags = BackendFeatureFlags(telegramBot = true),
            telegramTokenEncryptionKey = TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY,
        )
        val dataSource = HikariDataSource()
        val di = testDi(appConfig, dataSource)

        try {
            val httpDependencies = di.direct.instance<BackendHttpDependencies>()

            assertSame(
                di.direct.instance<TelegramBotBindingService>(),
                httpDependencies.telegramBotBindingService,
            )
        } finally {
            di.direct.instance<BackendRuntimeResources>().close()
        }
    }

    @Test
    fun `backend catalog excludes desktop sound configuration tools`() {
        val dataSource = HikariDataSource()
        val di = testDi(testAppConfig(), dataSource)

        try {
            val toolsByCategory = di.direct.instance<AgentToolCatalog>()
                .toolsByCategory

            val configTools = toolsByCategory.getValue(ToolCategory.CONFIG)

            assertTrue(configTools.isEmpty())
            assertEquals(setOf("WebPageText"), toolsByCategory.getValue(ToolCategory.WEB_SEARCH).keys)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `backend binds filesystem and bundled Skill runtime dependencies`() {
        val dataSource = HikariDataSource()
        val di = testDi(testAppConfig(), dataSource)

        try {
            assertIs<FileSystemSkillRegistryRepository>(di.direct.instance<SkillRegistryRepository>())
            assertIs<BackendClientSkills>(di.direct.instance<BackendClientSkills>())
            assertIs<SkillCommandExecutor>(di.direct.instance<SkillCommandExecutor>())
            assertNotNull(di.direct.instance<ConversationKnowledgeStore>())
            assertNotNull(
                di.direct.instance<LLMToolSetup>(tag = SkillToolBindingTags.GET_KNOWLEDGE_TOOL)
            )
            assertNotNull(
                di.direct.instance<LLMToolSetup>(tag = SkillToolBindingTags.SEARCH_KNOWLEDGE_TOOL)
            )
            assertNotNull(
                di.direct.instance<LLMToolSetup>(tag = SkillToolBindingTags.SEARCH_MEMORY_TOOL)
            )
        } finally {
            dataSource.close()
        }
    }

    private fun testDi(
        appConfig: BackendAppConfig,
        dataSource: HikariDataSource,
    ): DI = DI {
        import(
            backendDiModule(
                systemPrompt = "test-system-prompt",
                appConfig = appConfig,
                dataSourceFactory = { dataSource },
            )
        )
    }

    private fun testAppConfig(
        featureFlags: BackendFeatureFlags = BackendFeatureFlags(),
        telegramTokenEncryptionKey: String? = null,
        includeSkillOAuthConfig: Boolean = true,
    ): BackendAppConfig = BackendAppConfig(
        featureFlags = featureFlags,
        server = BackendServerConfig(
            host = "127.0.0.1",
            port = 8080,
            proxyToken = "test-proxy-token",
        ),
        postgres = BackendPostgresConfig(
            host = "127.0.0.1",
            port = 5432,
            database = "souz",
            user = "souz",
            password = null,
            schema = "public",
            maxPoolSize = 4,
            connectionTimeoutMs = 30_000L,
        ),
        masterKey = "test-master-key",
        telegramTokenEncryptionKey = telegramTokenEncryptionKey,
        skillOAuthTokenEncryptionKey = if (includeSkillOAuthConfig) TEST_SKILL_OAUTH_TOKEN_ENCRYPTION_KEY else null,
        skillOAuthProviderCredentials = if (includeSkillOAuthConfig) {
            mapOf(
                "yandex" to SkillOAuthProviderCredentials(
                    clientId = "test-yandex-client-id",
                    clientSecret = "test-yandex-client-secret",
                    redirectUri = "https://backend.test/oauth/callback",
                )
            )
        } else {
            emptyMap()
        },
    )

    private companion object {
        const val TEST_TELEGRAM_TOKEN_ENCRYPTION_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
        const val TEST_SKILL_OAUTH_TOKEN_ENCRYPTION_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
