package ru.souz.runtime.di

import com.fasterxml.jackson.databind.ObjectMapper
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LlmProvider
import ru.souz.llms.SessionTokenLogging
import ru.souz.llms.TokenLogging
import ru.souz.llms.TokenLoggingChatApi
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.giga.GigaAuth
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.http.GigaHttpClientResource
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.local.LocalBridgeLoader
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalHostInfoProvider
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalNativeBridge
import ru.souz.llms.local.LocalPromptRenderer
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.llms.local.LocalStrictJsonParser
import ru.souz.llms.local.LocalVisionGateway
import ru.souz.llms.anthropic.AnthropicVisionGateway
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import ru.souz.llms.openai.OpenAIVisionGateway
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.runtime.CapabilityBasedImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.LLMCapabilityResolver
import ru.souz.llms.runtime.SettingsRoutingLlmChatApi
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.tunnel.AiTunnelChatAPI
import ru.souz.paths.DefaultSouzPaths
import ru.souz.paths.SouzPaths

fun runtimeCoreDiModule(): DI.Module = DI.Module("runtimeCore") {
    bindSingleton { ConfigStore }
    bindSingleton<SouzPaths> { DefaultSouzPaths() }
    bindSingleton { LocalHostInfoProvider() }
    bindSingleton { LocalModelStore() }
    bindSingleton { LocalBridgeLoader(instance()) }
    bindSingleton { LocalNativeBridge(instance()) }
    bindSingleton { LocalPromptRenderer() }
    bindSingleton { LocalStrictJsonParser() }
    bindSingleton { LocalProviderAvailability(instance(), instance(), instance()) }
    bindSingleton<SettingsProvider> { SettingsProviderImpl(instance(), instance()) }
}

fun runtimeLlmDiModule(
    logObjectMapperTag: Any? = null,
): DI.Module = DI.Module("runtimeLlm") {
    import(runtimeProviderHttpDiModule())
    import(runtimeLocalLlmDiModule())
    bindSingleton<TokenLogging> {
        SessionTokenLogging(logObjectMapper = instance<ObjectMapper>(tag = logObjectMapperTag))
    }
    bindSingleton { GigaHttpClientResource() }
    bindSingleton { GigaAuth(instance(), instance<GigaHttpClientResource>().client) }
    bindSingleton<GigaRestChatAPI> {
        GigaRestChatAPI(instance(), instance(), instance<GigaHttpClientResource>().client)
    }
    bindSingleton<QwenChatAPI> { QwenChatAPI(instance(), instance<ProviderHttpClients>().standard) }
    bindSingleton<AiTunnelChatAPI> { AiTunnelChatAPI(instance(), instance<ProviderHttpClients>().standard) }
    bindSingleton<AnthropicChatAPI> { AnthropicChatAPI(instance(), instance<ProviderHttpClients>().standard) }
    bindSingleton<OpenAIChatAPI> { OpenAIChatAPI(instance(), instance<ProviderHttpClients>().openAi) }
    bindSingleton { OpenAIImageGenerationGateway(instance(), instance<ProviderHttpClients>().openAi) }
    bindSingleton<CodexChatAPI> {
        CodexChatAPI(instance(), instance(), instance<ProviderHttpClients>().standard)
    }
    bindSingleton { OpenAIVisionGateway(instance(), instance()) }
    bindSingleton { AnthropicVisionGateway(instance(), instance()) }
    bindSingleton {
        SettingsRoutingLlmChatApi(
            settingsProvider = instance(),
            apisByProvider = mapOf(
                LlmProvider.GIGA to instance<GigaRestChatAPI>(),
                LlmProvider.QWEN to instance<QwenChatAPI>(),
                LlmProvider.AI_TUNNEL to instance<AiTunnelChatAPI>(),
                LlmProvider.ANTHROPIC to instance<AnthropicChatAPI>(),
                LlmProvider.OPENAI to instance<OpenAIChatAPI>(),
                LlmProvider.LOCAL to instance<LocalChatAPI>(),
                LlmProvider.CODEX to instance<CodexChatAPI>(),
            ),
        )
    }
    bindSingleton<LLMChatAPI> {
        TokenLoggingChatApi(
            delegate = instance<SettingsRoutingLlmChatApi>(),
            tokenLogging = instance(),
        )
    }
    bindSingleton { LocalVisionGateway(instance(), instance<LLMChatAPI>()) }
    bindSingleton {
        LLMCapabilityResolver(
            settingsProvider = instance(),
            openAiGateway = instance(),
            anthropicGateway = instance(),
            additionalGateways = mapOf(LlmProvider.LOCAL to instance<LocalVisionGateway>()),
        )
    }
    bindSingleton { CapabilityBasedImageGenerationGateway(instance(), instance()) }
    bindSingleton<VisionGateway> { instance<LLMCapabilityResolver>() }
    bindSingleton<ImageGenerationGateway> { instance<CapabilityBasedImageGenerationGateway>() }
}

/** Process-owned remote transports that are safe for backend and interactive hosts. */
fun runtimeProviderHttpDiModule(): DI.Module = DI.Module("runtimeProviderHttp") {
    bindSingleton { ProviderHttpClients() }
    bindSingleton {
        CodexOAuthService(
            settingsProvider = instance(),
            client = instance<ProviderHttpClients>().standard,
        )
    }
}

/** Concrete local runtime bindings without remote providers or interactive observability. */
fun runtimeLocalLlmDiModule(): DI.Module = DI.Module("runtimeLocalLlm") {
    bindSingleton { LocalLlamaRuntime(instance(), instance(), instance(), instance(), instance()) }
    bindSingleton<LocalChatAPI> { LocalChatAPI(instance()) }
}
