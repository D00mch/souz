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
import org.kodein.di.instanceOrNull
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.SkillToolBindingTags
import ru.souz.backend.agent.runtime.BackendSandboxScopeResolver
import ru.souz.backend.agent.runtime.BackendConversationRuntimeTurnRunner
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntimeFactory
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.channels.PublicClientChannelProvider
import ru.souz.backend.channels.TelegramChannelProvider
import ru.souz.backend.channels.tool.BackendChannelToolCatalog
import ru.souz.backend.channels.tool.ToolListActiveChannels
import ru.souz.backend.channels.tool.ToolSendMessageToChannel
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
import ru.souz.backend.common.BackendLlmSupport
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
import ru.souz.backend.llm.ProviderCredentialResolver
import ru.souz.backend.llm.StoredProviderCredentialResolver
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.onboarding.BackendOnboardingService
import ru.souz.backend.settings.repository.BackendServerPreferenceStore
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.service.BackendSettingsProvider
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.settings.service.UserSettingsService
import ru.souz.backend.storage.postgres.PostgresAgentEventRepository
import ru.souz.backend.storage.postgres.PostgresAgentExecutionRepository
import ru.souz.backend.storage.postgres.PostgresAgentStateRepository
import ru.souz.backend.storage.postgres.PostgresBackendServerPreferenceStore
import ru.souz.backend.storage.postgres.PostgresChatRepository
import ru.souz.backend.storage.postgres.PostgresClientInputRepository
import ru.souz.backend.storage.postgres.PostgresClientRequestRepository
import ru.souz.backend.storage.postgres.PostgresOptionRepository
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.PostgresMessageRepository
import ru.souz.backend.storage.postgres.PostgresToolCallRepository
import ru.souz.backend.storage.postgres.PostgresTelegramBotBindingRepository
import ru.souz.backend.storage.postgres.PostgresUserRepository
import ru.souz.backend.storage.postgres.PostgresUserProviderKeyRepository
import ru.souz.backend.storage.postgres.PostgresUserSettingsRepository
import ru.souz.backend.toolcall.repository.ToolCallRepository
import ru.souz.backend.user.repository.UserRepository
import ru.souz.db.SettingsProvider
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.runtime.di.runtimeCoreDiModule
import ru.souz.runtime.di.runtimeLocalLlmDiModule
import ru.souz.runtime.di.runtimeProviderHttpDiModule
import ru.souz.runtime.files.FilesToolUtil
import ru.souz.skills.registry.fileSystemSkillRegistryDiModule
import ru.souz.backend.telegram.HttpTelegramBotApi
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotBindingService
import ru.souz.backend.telegram.TelegramBotPollingService
import ru.souz.backend.telegram.TelegramBotTokenCrypto
import ru.souz.skilloauth.impl.SkillOAuthGatewayImpl
import ru.souz.tool.RuntimeToolsFactory
import ru.souz.tool.composeToolCatalogs
import ru.souz.tool.runtimeToolsDiModule
import ru.souz.tool.portableSkillRuntimeToolsDiModule
import ru.souz.tool.skills.SkillCommandExecutor
import ru.souz.tool.web.internal.WebResearchClient

private object BackendDiTags {
    const val LOG_OBJECT_MAPPER = "backendLogObjectMapper"
    const val MERGED_TOOL_CATALOG = "backendMergedToolCatalog"
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

    import(runtimeCoreDiModule(bindSettingsProvider = false))
    import(
        runtimeToolsDiModule(
            includeWebImageSearch = false,
            scopeResolver = BackendSandboxScopeResolver,
        )
    )
    import(runtimeProviderHttpDiModule())
    import(runtimeLocalLlmDiModule())
    import(fileSystemSkillRegistryDiModule())
    import(portableSkillRuntimeToolsDiModule())
    val skillOAuthConfig = SkillOAuthBackendConfig.from(appConfig)
    import(skillOAuthBackendModule(skillOAuthConfig))

    bindSingleton { BackendApplicationScope() }
    bindSingleton<Clock> { Clock.systemUTC() }
    bindSingleton<BackendFeatureFlags> { appConfig.featureFlags }
    bindSingleton<HikariDataSource> {
        dataSourceFactory(appConfig.postgres)
    }
    bindSingleton<BackendServerPreferenceStore> {
        PostgresBackendServerPreferenceStore(
            dataSource = instance(),
            masterKey = appConfig.masterKey ?: error("Master key is required."),
        )
    }
    bindSingleton<SettingsProvider> {
        BackendSettingsProvider(
            preferenceStore = instance(),
            localProviderAvailability = instance<LocalProviderAvailability>(),
        )
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
    bindSingleton {
        // Each AuthorizationCodeOAuthClient and SkillOAuthGatewayImpl owns its own Ktor CIO
        // HttpClient (a selector-manager thread pool each); without closing them here they leak
        // past backend shutdown.
        BackendRuntimeResources(
            cancelAndJoinApplicationWork = { instance<BackendApplicationScope>().cancelAndJoin() },
            closeProviderClients = { instance<ProviderHttpClients>().close() },
            closeLocalRuntime = { instance<ru.souz.llms.local.LocalLlamaRuntime>().close() },
            closeSkillOAuthClients = {
                skillOAuthConfig?.providers?.values.orEmpty().filterIsInstance<AutoCloseable>().forEach { it.close() }
                instanceOrNull<SkillOAuthGatewayImpl>()?.close()
            },
            closeDataSource = { instance<HikariDataSource>().close() },
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
            baseSettingsProvider = instance(),
            userProviderKeyService = instance(),
        )
    }
    bindSingleton {
        EffectiveSettingsResolver(
            baseSettingsProvider = instance(),
            userSettingsRepository = instance(),
            userProviderKeyRepository = instance(),
            featureFlags = instance(),
            toolCatalog = instance<AgentToolCatalog>(tag = BackendDiTags.MERGED_TOOL_CATALOG),
            localModelAvailability = instance<LocalProviderAvailability>(),
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
            baseSettingsProvider = instance(),
            credentialResolver = instance(),
            retryPolicy = appConfig.providerRetryPolicy,
            providerHttpClients = instance(),
            localChatApi = instance<LocalChatAPI>(),
            codexOAuthService = instance<CodexOAuthService>(),
            sessionRepository = instance(),
            logObjectMapper = instance(BackendDiTags.LOG_OBJECT_MAPPER),
            systemPrompt = systemPrompt,
            toolCatalog = instance<AgentToolCatalog>(tag = BackendDiTags.MERGED_TOOL_CATALOG),
            clientToolCatalog = instance<BackendClientSkills>(),
            skillBundleProvider = instance<SkillRegistryRepository>(),
            commandExecutor = instance<SkillCommandExecutor>(),
            filesToolUtil = instance<FilesToolUtil>(),
            webResearchClient = instance<WebResearchClient>(),
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
        bindSingleton {
            TelegramChannelProvider(
                bindingRepository = instance(),
                chatRepository = instance(),
                messageRepository = instance(),
                eventService = instance(),
                telegramBotApi = instance(),
                tokenCrypto = instance(),
            )
        }
    }
    bindSingleton {
        val telegramBindingRepository = instance<TelegramBotBindingRepository>()
        PublicClientChannelProvider(
            chatRepository = instance(),
            messageRepository = instance(),
            eventService = instance(),
            isClaimedByAnotherProvider = { chatId ->
                // Only an active Telegram binding actually claims the chat away from the public-client
                // provider — a pending (not yet /start-linked) or disabled binding must not hide the
                // chat from ListActiveChannels, since TelegramChannelProvider itself won't list it either.
                telegramBindingRepository.getByChat(chatId)?.let { it.enabled && it.linked } == true
            },
        )
    }
    bindSingleton {
        ChannelProviderRegistry(
            providers = listOfNotNull(
                if (appConfig.featureFlags.telegramBot) instance<TelegramChannelProvider>() else null,
                instance<PublicClientChannelProvider>(),
            )
        )
    }
    bindSingleton { ToolListActiveChannels(registry = instance()) }
    bindSingleton { ToolSendMessageToChannel(registry = instance()) }
    bindSingleton { BackendChannelToolCatalog(instance(), instance()) }
    bindSingleton<AgentToolCatalog>(tag = BackendDiTags.MERGED_TOOL_CATALOG) {
        composeToolCatalogs(listOf(instance<RuntimeToolsFactory>(), instance<BackendChannelToolCatalog>()))
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
            settingsProvider = instance(),
            effectiveSettingsResolver = instance(),
            toolCatalog = instance<AgentToolCatalog>(tag = BackendDiTags.MERGED_TOOL_CATALOG),
            featureFlags = instance(),
            localModelAvailability = instance<LocalProviderAvailability>(),
            userProviderKeyRepository = instance(),
        )
    }
    bindSingleton {
        val featureFlags = instance<BackendFeatureFlags>()
        val settingsProvider = instance<SettingsProvider>()
        val userRepository = instance<UserRepository>()
        BackendHttpDependencies(
            bootstrapService = instance(),
            skillOAuthGatewayImpl = if (skillOAuthConfig != null) instance() else null,
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
            selectedModel = {
                settingsProvider.gigaModel
                    .takeIf { it in BackendLlmSupport.chatModels }
                    ?.alias
                    ?: BackendLlmSupport.fallbackChatModel.alias
            },
            trustedProxyToken = { appConfig.server.proxyToken },
            ensureTrustedUser = userRepository::ensureUser,
        )
    }
}
