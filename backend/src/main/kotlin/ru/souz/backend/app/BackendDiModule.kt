package ru.souz.backend.app

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariDataSource
import java.time.Clock
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.SkillToolBindingTags
import ru.souz.backend.agent.runtime.BackendSandboxUnavailableSkillCommandRunner
import ru.souz.backend.agent.runtime.BackendConversationRuntimeTurnRunner
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntimeFactory
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.chat.service.MessageService
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.client.PublicClientService
import ru.souz.backend.client.ClientThreadRecoveryService
import ru.souz.backend.client.repository.ClientInputRepository
import ru.souz.backend.client.repository.ClientRequestRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.config.BackendSettingsConfig
import ru.souz.backend.common.BackendSafeToolCatalog
import ru.souz.backend.options.repository.OptionRepository
import ru.souz.backend.options.service.OptionService
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.events.bus.AgentEventBus
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.execution.service.AgentExecutionFinalizer
import ru.souz.backend.execution.service.AgentExecutionLauncher
import ru.souz.backend.execution.service.AgentExecutionRequestFactory
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.llm.BackendLlmClientFactory
import ru.souz.backend.llm.BackendProviderHttpClients
import ru.souz.backend.llm.LlmClientFactory
import ru.souz.backend.llm.ProviderChatApiBuilder
import ru.souz.backend.llm.ProviderCredentialResolver
import ru.souz.backend.llm.RuntimeProviderChatApiBuilder
import ru.souz.backend.llm.StoredProviderCredentialResolver
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.onboarding.BackendOnboardingService
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.settings.service.UserSettingsService
import ru.souz.backend.storage.postgres.PostgresAgentEventRepository
import ru.souz.backend.storage.postgres.PostgresAgentExecutionRepository
import ru.souz.backend.storage.postgres.PostgresAgentStateRepository
import ru.souz.backend.storage.postgres.PostgresChatRepository
import ru.souz.backend.storage.postgres.PostgresClientInputRepository
import ru.souz.backend.storage.postgres.PostgresClientRequestRepository
import ru.souz.backend.storage.postgres.PostgresCodexOAuthCredentialStore
import ru.souz.backend.storage.postgres.PostgresConversationKnowledgeStore
import ru.souz.backend.storage.postgres.PostgresOptionRepository
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresMessageRepository
import ru.souz.backend.storage.postgres.PostgresSkillRegistryRepository
import ru.souz.backend.storage.postgres.PostgresToolCallRepository
import ru.souz.backend.storage.postgres.PostgresTelegramBotBindingRepository
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.PostgresUserProviderKeyRepository
import ru.souz.backend.storage.postgres.PostgresUserSettingsRepository
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.backend.user.repository.UserRepository
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.SessionTokenLogging
import ru.souz.llms.TokenLogging
import ru.souz.llms.codex.CodexOAuthCredentialStore
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.backend.skills.BackendSkillBundleProvider
import ru.souz.backend.telegram.HttpTelegramBotApi
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotBindingService
import ru.souz.backend.telegram.TelegramBotPollingService
import ru.souz.backend.telegram.TelegramBotTokenCrypto
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.tool.knowledge.ToolGetKnowledge
import ru.souz.tool.knowledge.ToolSearchKnowledge
import ru.souz.tool.memory.ToolSearchMemory
import ru.souz.tool.skills.SkillCommandRunner

private object BackendDiTags {
    const val LOG_OBJECT_MAPPER = "backendLogObjectMapper"
}

/** Backend Kodein module that wires HTTP services to the shared JVM runtime. */
fun backendDiModule(
    systemPrompt: String,
    appConfig: BackendAppConfig,
    dataSourceFactory: (BackendPostgresConfig) -> HikariDataSource = PostgresDataSourceFactory::create,
): DI.Module = DI.Module("backend") {
    bindSingleton<ObjectMapper>(tag = BackendDiTags.LOG_OBJECT_MAPPER) {
        jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
    }

    bindSingleton { BackendApplicationScope() }
    bindSingleton<Clock> { Clock.systemUTC() }
    bindSingleton<BackendFeatureFlags> { appConfig.featureFlags }
    bindSingleton { appConfig.settings }
    bindSingleton<TokenLogging> {
        SessionTokenLogging(instance<ObjectMapper>(tag = BackendDiTags.LOG_OBJECT_MAPPER))
    }
    bindSingleton<AgentToolCatalog> { BackendSafeToolCatalog() }
    bindSingleton<HikariDataSource> {
        dataSourceFactory(appConfig.postgres)
    }
    bindSingleton<UserRepository> { PostgresUserRepository(instance()) }
    bindSingleton<ChatRepository> { PostgresChatRepository(instance()) }
    bindSingleton<ClientRequestRepository> { PostgresClientRequestRepository(instance()) }
    bindSingleton<ClientInputRepository> { PostgresClientInputRepository(instance()) }
    bindSingleton<MessageRepository> { PostgresMessageRepository(instance()) }
    bindSingleton<AgentStateRepository> { PostgresAgentStateRepository(instance()) }
    bindSingleton<AgentExecutionRepository> { PostgresAgentExecutionRepository(instance()) }
    bindSingleton<OptionRepository> { PostgresOptionRepository(instance()) }
    bindSingleton<AgentEventRepository> { PostgresAgentEventRepository(instance()) }
    bindSingleton<ToolCallRepository> { PostgresToolCallRepository(instance()) }
    bindSingleton<UserSettingsRepository> { PostgresUserSettingsRepository(instance()) }
    bindSingleton<UserProviderKeyRepository> { PostgresUserProviderKeyRepository(instance()) }
    bindSingleton<TelegramBotBindingRepository> { PostgresTelegramBotBindingRepository(instance()) }
    bindSingleton<SkillRegistryRepository> {
        PostgresSkillRegistryRepository(
            dataSource = instance(),
            builtInSkillBundleHashes = instance<BackendClientSkills>().bundleHashesBySkillId,
        )
    }
    bindSingleton<SkillBundleProvider> {
        BackendSkillBundleProvider(
            resourceSkills = instance<BackendClientSkills>(),
            userSkills = instance<SkillRegistryRepository>(),
        )
    }
    bindSingleton<ConversationKnowledgeStore> { PostgresConversationKnowledgeStore(instance()) }
    bindSingleton<SkillCommandRunner> { BackendSandboxUnavailableSkillCommandRunner }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.GET_KNOWLEDGE_TOOL) {
        ToolGetKnowledge(instance<ConversationKnowledgeStore>())
    }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.SEARCH_KNOWLEDGE_TOOL) {
        ToolSearchKnowledge(instance<ConversationKnowledgeStore>())
    }
    bindSingleton<LLMToolSetup>(tag = SkillToolBindingTags.SEARCH_MEMORY_TOOL) {
        ToolSearchMemory(NoopConversationMemoryRuntime)
    }
    bindSingleton {
        PostgresCodexOAuthCredentialStore(
            dataSource = instance<HikariDataSource>(),
            masterKey = appConfig.masterKey ?: error("Master key is required."),
            initialSeed = appConfig.codexOAuthSeed,
        )
    }
    bindSingleton<CodexOAuthCredentialStore> { instance<PostgresCodexOAuthCredentialStore>() }
    bindSingleton { BackendProviderHttpClients() }
    bindSingleton {
        CodexOAuthService(
            credentialStore = instance<CodexOAuthCredentialStore>(),
            httpClient = instance<BackendProviderHttpClients>().clientFor(LlmProvider.CODEX),
        )
    }
    bindSingleton {
        BackendRuntimeResources(
            closeables = listOf(
                instance<BackendApplicationScope>(),
                instance<BackendProviderHttpClients>(),
                instance<HikariDataSource>(),
            )
        )
    }
    bindSingleton { AgentEventBus() }
    bindSingleton { ClientThreadRuntimeRegistry() }
    bindSingleton {
        UserProviderKeyService(
            repository = instance(),
            masterKey = appConfig.masterKey ?: error("Master key is required."),
        )
    }
    bindSingleton {
        AgentEventService(
            chatRepository = instance(),
            eventRepository = instance(),
            eventBus = instance(),
        )
    }
    bindSingleton { ExecutionQuotaManager(appConfig.llmLimits) }
    bindSingleton<ProviderCredentialResolver> {
        StoredProviderCredentialResolver(
            settingsConfig = instance<BackendSettingsConfig>(),
            userProviderKeyService = instance(),
            codexOAuthCredentialStore = instance(),
        )
    }
    bindSingleton<ProviderChatApiBuilder> {
        RuntimeProviderChatApiBuilder(
            tokenLogging = instance(),
            retryPolicy = appConfig.providerRetryPolicy,
            codexOAuthService = instance<CodexOAuthService>(),
            providerHttpClients = instance<BackendProviderHttpClients>(),
        )
    }
    bindSingleton<LlmClientFactory> {
        BackendLlmClientFactory(
            credentialResolver = instance(),
            providerClientFactory = instance(),
        )
    }
    bindSingleton {
        EffectiveSettingsResolver(
            baseSettings = instance<BackendSettingsConfig>(),
            userSettingsRepository = instance(),
            userProviderKeyRepository = instance(),
            featureFlags = instance(),
            toolCatalog = instance(),
            codexOAuthCredentialStore = instance(),
        )
    }
    bindSingleton<AgentSessionRepository> {
        AgentStateBackedSessionRepository(instance())
    }
    bindSingleton {
        UserSettingsService(
            userSettingsRepository = instance(),
            effectiveSettingsResolver = instance(),
        )
    }
    bindSingleton {
        BackendOnboardingService(
            bootstrapService = instance(),
            userSettingsRepository = instance(),
            userSettingsService = instance(),
        )
    }
    bindSingleton {
        ChatService(
            chatRepository = instance(),
            messageRepository = instance(),
        )
    }
    bindSingleton {
        BackendClientSkills(
            registry = instance(),
            toolCallRepository = instance(),
            eventService = instance(),
        )
    }
    bindSingleton {
        BackendConversationRuntimeFactory(
            baseSettings = instance<BackendSettingsConfig>(),
            llmApiFactory = { executionContext -> instance<LlmClientFactory>().create(executionContext) },
            sessionRepository = instance(),
            logObjectMapper = instance(BackendDiTags.LOG_OBJECT_MAPPER),
            systemPrompt = systemPrompt,
            toolCatalog = instance(),
            clientToolCatalog = instance<BackendClientSkills>(),
            clientSkillBundleProvider = instance<SkillBundleProvider>(),
            userSkillBundleProvider = instance<SkillRegistryRepository>(),
            commandExecutor = instance<SkillCommandRunner>(),
            getKnowledgeTool = instance(tag = SkillToolBindingTags.GET_KNOWLEDGE_TOOL),
            searchKnowledgeTool = instance(tag = SkillToolBindingTags.SEARCH_KNOWLEDGE_TOOL),
            searchMemoryTool = instance(tag = SkillToolBindingTags.SEARCH_MEMORY_TOOL),
            knowledgeStore = instance<ConversationKnowledgeStore>(),
            agentBackgroundScope = instance<BackendApplicationScope>(),
        )
    }
    bindSingleton {
        AgentExecutionRequestFactory(
            effectiveSettingsResolver = instance(),
            featureFlags = instance(),
            clientThreadRegistry = instance(),
        )
    }
    bindSingleton {
        AgentExecutionFinalizer(
            agentStateRepository = instance(),
            chatRepository = instance(),
            executionRepository = instance(),
            turnRunner = BackendConversationRuntimeTurnRunner(instance(), instance()),
            clientThreadRegistry = instance(),
        )
    }
    bindSingleton {
        AgentExecutionLauncher(
            executionScope = instance<BackendApplicationScope>(),
            finalizer = instance(),
            executionRepository = instance(),
            clientThreadRegistry = instance(),
        )
    }
    bindSingleton {
        AgentExecutionService(
            chatRepository = instance(),
            messageRepository = instance(),
            executionRepository = instance(),
            clientRequestRepository = instance(),
            optionRepository = instance(),
            eventService = instance(),
            toolCallRepository = instance(),
            requestFactory = instance(),
            finalizer = instance(),
            launcher = instance(),
        )
    }
    if (appConfig.featureFlags.telegramBot) {
        bindSingleton<TelegramBotApi> { HttpTelegramBotApi() }
        bindSingleton {
            TelegramBotTokenCrypto(
                rawBase64Key = appConfig.telegramTokenEncryptionKey
                    ?: error("Telegram token encryption key is required.")
            )
        }
        bindSingleton {
            TelegramBotBindingService(
                chatRepository = instance(),
                bindingRepository = instance(),
                telegramBotApi = instance(),
                tokenCrypto = instance(),
                clock = instance(),
            )
        }
        bindSingleton {
            TelegramBotPollingService(
                repository = instance(),
                botApi = instance(),
                executionService = instance(),
                tokenCrypto = instance(),
                scope = instance<BackendApplicationScope>(),
                maxConcurrency = appConfig.telegramPollingMaxConcurrency,
            )
        }
    }
    bindSingleton {
        OptionService(
            optionRepository = instance(),
            executionService = instance(),
            featureFlags = instance(),
        )
    }
    bindSingleton {
        MessageService(
            chatRepository = instance(),
            messageRepository = instance(),
            executionService = instance(),
        )
    }
    bindSingleton {
        PublicClientService(
            chatRepository = instance(),
            executionRepository = instance(),
            clientInputRepository = instance(),
            clientRequestRepository = instance(),
            toolCallRepository = instance(),
            executionService = instance(),
            registry = instance(),
        )
    }
    bindSingleton {
        ClientThreadRecoveryService(
            executionRepository = instance(),
            eventService = instance(),
            clock = instance(),
        )
    }
    bindSingleton {
        BackendBootstrapService(
            settingsConfig = instance<BackendSettingsConfig>(),
            effectiveSettingsResolver = instance(),
            toolCatalog = instance(),
            featureFlags = instance(),
            userProviderKeyRepository = instance(),
            codexOAuthCredentialStore = instance(),
        )
    }
    bindSingleton {
        val featureFlags = instance<BackendFeatureFlags>()
        val settings = instance<BackendSettingsConfig>()
        val userRepository = instance<UserRepository>()
        BackendHttpDependencies(
            bootstrapService = instance(),
            onboardingService = instance(),
            userSettingsService = instance(),
            providerKeyService = instance(),
            chatService = instance(),
            messageService = instance(),
            executionService = instance(),
            optionService = instance(),
            eventService = instance(),
            publicClientService = instance(),
            telegramBotBindingService = if (featureFlags.telegramBot) instance() else null,
            featureFlags = featureFlags,
            selectedModel = { settings.gigaModel.alias },
            trustedProxyToken = { appConfig.server.proxyToken },
            ensureTrustedUser = userRepository::ensureUser,
        )
    }
}
