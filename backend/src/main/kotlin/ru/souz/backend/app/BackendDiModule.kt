package ru.souz.backend.app

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.backend.app.BackendAppConfig
import ru.souz.backend.agent.runtime.BackendConversationRuntimeFactory
import ru.souz.backend.agent.service.BackendAgentService
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.config.BackendFeatureFlags
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
    bindSingleton<AgentSessionRepository> { InMemoryAgentSessionRepository() }
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
            toolCatalog = instance(),
            featureFlags = instance(),
            storageMode = instance(),
            localModelAvailability = instance<LocalProviderAvailability>(),
        )
    }
}
