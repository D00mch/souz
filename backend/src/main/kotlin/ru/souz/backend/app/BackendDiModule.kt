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
import ru.souz.agent.spi.SkillToolBindingTags
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.agent.runtime.BackendSandboxScopeResolver
import ru.souz.backend.agent.runtime.BackendConversationRuntimeFactory
import ru.souz.backend.agent.runtime.BackendConversationRuntimeTurnRunner
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.chat.service.MessageService
import ru.souz.backend.config.BackendFeatureFlags
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
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.runtime.di.runtimeCoreDiModule
import ru.souz.runtime.di.runtimeLlmDiModule
import ru.souz.backend.telegram.HttpTelegramBotApi
import ru.souz.backend.telegram.TelegramBotApi
import ru.souz.backend.telegram.TelegramBotBindingRepository
import ru.souz.backend.telegram.TelegramBotBindingService
import ru.souz.backend.telegram.TelegramBotPollingService
import ru.souz.backend.telegram.TelegramBotTokenCrypto
import ru.souz.skills.registry.FileSystemSkillRegistryConfig
import ru.souz.skills.registry.SkillStorageScope
import ru.souz.tool.runtimeToolsDiModule

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

    import(
        runtimeCoreDiModule(
            skillRegistryConfig = FileSystemSkillRegistryConfig(scope = SkillStorageScope.USER_SCOPED)
        )
    )
    import(
        runtimeToolsDiModule(
            includeWebImageSearch = false,
            skillStorageScope = SkillStorageScope.USER_SCOPED,
            scopeResolver = BackendSandboxScopeResolver,
        )
    )
    import(runtimeLlmDiModule(logObjectMapperTag = BackendDiTags.LOG_OBJECT_MAPPER))

    bindSingleton { BackendApplicationScope() }
    bindSingleton<Clock> { Clock.systemUTC() }
    bindSingleton<BackendFeatureFlags> { appConfig.featureFlags }
    bindSingleton<HikariDataSource> {
        dataSourceFactory(appConfig.postgres)
    }
    bindSingleton<UserRepository> { PostgresUserRepository(instance()) }
    bindSingleton<ChatRepository> { PostgresChatRepository(instance()) }
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
        BackendRuntimeResources(
            closeables = listOf(
                instance<BackendApplicationScope>(),
                instance<HikariDataSource>(),
            )
        )
    }
    bindSingleton { AgentEventBus() }
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
    bindSingleton<ProviderChatApiBuilder> {
        RuntimeProviderChatApiBuilder(
            tokenLogging = instance(),
            retryPolicy = appConfig.providerRetryPolicy,
        )
    }
    bindSingleton<LlmClientFactory> {
        BackendLlmClientFactory(
            credentialResolver = instance(),
            providerClientFactory = instance(),
            localChatApi = instance(),
        )
    }
    bindSingleton {
        EffectiveSettingsResolver(
            baseSettingsProvider = instance(),
            userSettingsRepository = instance(),
            userProviderKeyRepository = instance(),
            featureFlags = instance(),
            toolCatalog = instance(),
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
        BackendConversationRuntimeFactory(
            baseSettingsProvider = instance(),
            llmApiFactory = { executionContext -> instance<LlmClientFactory>().create(executionContext) },
            sessionRepository = instance(),
            logObjectMapper = instance(BackendDiTags.LOG_OBJECT_MAPPER),
            systemPrompt = systemPrompt,
            toolCatalog = instance(),
            toolsFilter = instance(),
            skillCommandTool = instance(tag = SkillToolBindingTags.COMMAND_TOOL),
            skillRegistryRepository = instance(),
            agentBackgroundScope = instance<BackendApplicationScope>(),
        )
    }
    bindSingleton {
        AgentExecutionRequestFactory(
            effectiveSettingsResolver = instance(),
            featureFlags = instance(),
        )
    }
    bindSingleton {
        AgentExecutionFinalizer(
            agentStateRepository = instance(),
            chatRepository = instance(),
            executionRepository = instance(),
            turnRunner = BackendConversationRuntimeTurnRunner(instance()),
        )
    }
    bindSingleton {
        AgentExecutionLauncher(
            executionScope = instance<BackendApplicationScope>(),
            finalizer = instance(),
        )
    }
    bindSingleton {
        AgentExecutionService(
            chatRepository = instance(),
            messageRepository = instance(),
            executionRepository = instance(),
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
        BackendBootstrapService(
            settingsProvider = instance(),
            effectiveSettingsResolver = instance(),
            toolCatalog = instance(),
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
            onboardingService = instance(),
            userSettingsService = instance(),
            providerKeyService = instance(),
            chatService = instance(),
            messageService = instance(),
            executionService = instance(),
            optionService = instance(),
            eventService = instance(),
            telegramBotBindingService = if (featureFlags.telegramBot) instance() else null,
            featureFlags = featureFlags,
            selectedModel = { settingsProvider.gigaModel.alias },
            trustedProxyToken = { appConfig.server.proxyToken },
            ensureTrustedUser = userRepository::ensureUser,
        )
    }
}
