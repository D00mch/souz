package ru.souz.backend.app

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.backend.agent.session.AgentStateBackedSessionRepository
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.agent.runtime.BackendConversationRuntimeFactory
import ru.souz.backend.agent.service.BackendAgentService
import ru.souz.backend.agent.session.AgentStateRepository
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.chat.repository.ChatRepository
import ru.souz.backend.chat.repository.MessageRepository
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.choices.repository.ChoiceRepository
import ru.souz.backend.events.repository.AgentEventRepository
import ru.souz.backend.execution.repository.AgentExecutionRepository
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.service.EffectiveSettingsResolver
import ru.souz.backend.storage.memory.MemoryAgentEventRepository
import ru.souz.backend.storage.memory.MemoryAgentExecutionRepository
import ru.souz.backend.storage.memory.MemoryAgentStateRepository
import ru.souz.backend.storage.memory.MemoryChatRepository
import ru.souz.backend.storage.memory.MemoryChoiceRepository
import ru.souz.backend.storage.memory.MemoryMessageRepository
import ru.souz.backend.storage.memory.MemoryUserSettingsRepository
import ru.souz.llms.runtime.LLMFactory
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.runtime.di.runtimeCoreDiModule
import ru.souz.runtime.di.runtimeLlmDiModule
import ru.souz.backend.storage.StorageMode
import ru.souz.tool.runtimeToolsDiModule

private object BackendDiTags {
    const val LOG_OBJECT_MAPPER = "backendLogObjectMapper"
}

/** Backend Kodein module that wires HTTP services to the shared JVM runtime. */
fun backendDiModule(
    systemPrompt: String,
    appConfig: BackendAppConfig,
): DI.Module = DI.Module("backend") {
    bindSingleton<ObjectMapper>(tag = BackendDiTags.LOG_OBJECT_MAPPER) {
        jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
    }

    import(runtimeCoreDiModule())
    import(runtimeToolsDiModule(includeWebImageSearch = false))
    import(runtimeLlmDiModule(logObjectMapperTag = BackendDiTags.LOG_OBJECT_MAPPER))

    bindSingleton<BackendFeatureFlags> { appConfig.featureFlags }
    bindSingleton<StorageMode> { appConfig.storageMode }
    bindSingleton<ChatRepository> { MemoryChatRepository() }
    bindSingleton<MessageRepository> { MemoryMessageRepository() }
    bindSingleton<AgentStateRepository> { MemoryAgentStateRepository() }
    bindSingleton<AgentExecutionRepository> { MemoryAgentExecutionRepository() }
    bindSingleton<ChoiceRepository> { MemoryChoiceRepository() }
    bindSingleton<AgentEventRepository> { MemoryAgentEventRepository() }
    bindSingleton<UserSettingsRepository> { MemoryUserSettingsRepository() }
    bindSingleton {
        EffectiveSettingsResolver(
            baseSettingsProvider = instance(),
            userSettingsRepository = instance(),
            featureFlags = instance(),
            toolCatalog = instance(),
            localModelAvailability = instance<LocalProviderAvailability>(),
        )
    }
    bindSingleton<AgentSessionRepository> {
        AgentStateBackedSessionRepository(instance())
    }
    bindSingleton {
        BackendConversationRuntimeFactory(
            baseSettingsProvider = instance(),
            llmApiFactory = { requestSettings ->
                LLMFactory(
                    settingsProvider = requestSettings,
                    restApi = instance(),
                    qwenApi = instance(),
                    aiTunnelApi = instance(),
                    anthropicApi = instance(),
                    openAiApi = instance(),
                    localApi = instance(),
                )
            },
            sessionRepository = instance(),
            logObjectMapper = instance(BackendDiTags.LOG_OBJECT_MAPPER),
            systemPrompt = systemPrompt,
            toolCatalog = instance(),
            toolsFilter = instance(),
        )
    }
    bindSingleton {
        BackendAgentService(
            baseSettingsProvider = instance(),
            runtimeFactory = instance(),
        )
    }
    bindSingleton {
        BackendBootstrapService(
            settingsProvider = instance(),
            effectiveSettingsResolver = instance(),
            toolCatalog = instance(),
            featureFlags = instance(),
            storageMode = instance(),
            localModelAvailability = instance<LocalProviderAvailability>(),
        )
    }
}
