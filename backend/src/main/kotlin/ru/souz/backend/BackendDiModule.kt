package ru.souz.backend

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.SessionTokenLogging
import ru.souz.llms.TokenLogging
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.giga.GigaAuth
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.local.LocalBridgeLoader
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalHostInfoProvider
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalNativeBridge
import ru.souz.llms.local.LocalPromptRenderer
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.llms.local.LocalStrictJsonParser
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.runtime.LLMFactory
import ru.souz.llms.tunnel.AiTunnelChatAPI
import ru.souz.tool.runtimeToolsDiModule

private object BackendDiTags {
    const val LOG_OBJECT_MAPPER = "backendLogObjectMapper"
}

fun backendDiModule(
    systemPrompt: String,
): DI.Module = DI.Module("backend") {
    bindSingleton<ObjectMapper>(tag = BackendDiTags.LOG_OBJECT_MAPPER) {
        jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT)
    }

    bindSingleton { ConfigStore }
    bindSingleton { LocalHostInfoProvider() }
    bindSingleton { LocalModelStore() }
    bindSingleton { LocalBridgeLoader(instance()) }
    bindSingleton { LocalProviderAvailability(instance(), instance(), instance()) }
    bindSingleton<SettingsProvider> { SettingsProviderImpl(instance(), instance()) }
    import(runtimeToolsDiModule(includeWebImageSearch = false))
    bindSingleton<TokenLogging> {
        SessionTokenLogging(logObjectMapper = instance(BackendDiTags.LOG_OBJECT_MAPPER))
    }
    bindSingleton { LocalLlamaRuntime(instance(), instance(), instance(), instance(), instance()) }
    bindSingleton { LocalNativeBridge(instance()) }
    bindSingleton { LocalPromptRenderer() }
    bindSingleton { LocalStrictJsonParser() }

    bindSingleton { GigaAuth(instance()) }
    bindSingleton<GigaRestChatAPI> { GigaRestChatAPI(instance(), instance(), instance()) }
    bindSingleton<QwenChatAPI> { QwenChatAPI(instance(), instance()) }
    bindSingleton<AiTunnelChatAPI> { AiTunnelChatAPI(instance(), instance()) }
    bindSingleton<AnthropicChatAPI> { AnthropicChatAPI(instance(), instance()) }
    bindSingleton<OpenAIChatAPI> { OpenAIChatAPI(instance(), instance()) }
    bindSingleton<LocalChatAPI> { LocalChatAPI(instance()) }
    bindSingleton { LLMFactory(instance(), instance(), instance(), instance(), instance(), instance(), instance()) }
    bindSingleton<LLMChatAPI> { instance<LLMFactory>() }

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
    bindSingleton { BackendConversationRuntimeCache(instance()) }
    bindSingleton {
        val settingsProvider: SettingsProvider = instance()
        ChatService(
            chatApi = instance(),
            settings = {
                BackendChatSettings(
                    model = settingsProvider.gigaModel.alias,
                    provider = settingsProvider.gigaModel.provider,
                    temperature = settingsProvider.temperature,
                    contextSize = settingsProvider.contextSize,
                )
            },
            systemPrompt = systemPrompt,
            tokenLogging = instance(),
        )
    }
    bindSingleton {
        BackendAgentService(
            baseSettingsProvider = instance(),
            runtimeCache = instance(),
        )
    }
}
