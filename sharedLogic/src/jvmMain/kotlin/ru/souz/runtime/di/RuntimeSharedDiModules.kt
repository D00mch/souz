package ru.souz.runtime.di

import com.fasterxml.jackson.databind.ObjectMapper
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LlmProvider
import ru.souz.llms.SessionTokenLogging
import ru.souz.llms.TokenLogging
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexOAuthService
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
import ru.souz.llms.local.LocalVisionGateway
import ru.souz.llms.anthropic.AnthropicVisionGateway
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.openai.OpenAIImageGenerationGateway
import ru.souz.llms.openai.OpenAIVisionGateway
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.runtime.CapabilityBasedImageGenerationGateway
import ru.souz.llms.runtime.ImageGenerationGateway
import ru.souz.llms.runtime.LLMCapabilityResolver
import ru.souz.llms.runtime.LLMFactory
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.tunnel.AiTunnelChatAPI
import ru.souz.paths.DefaultSouzPaths
import ru.souz.paths.SouzPaths
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.skills.registry.FileSystemSkillRegistryConfig
import ru.souz.skills.registry.FileSystemSkillRegistryRepository

fun runtimeCoreDiModule(
    skillRegistryConfig: FileSystemSkillRegistryConfig = FileSystemSkillRegistryConfig(),
): DI.Module = DI.Module("runtimeCore") {
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
    bindSingleton<SkillRegistryRepository> {
        FileSystemSkillRegistryRepository(
            sandboxResolver = instance<ToolInvocationRuntimeSandboxResolver>(),
            config = skillRegistryConfig,
        )
    }
}

fun runtimeLlmDiModule(
    logObjectMapperTag: Any? = null,
): DI.Module = DI.Module("runtimeLlm") {
    bindSingleton<TokenLogging> {
        SessionTokenLogging(logObjectMapper = instance<ObjectMapper>(tag = logObjectMapperTag))
    }
    bindSingleton { LocalLlamaRuntime(instance(), instance(), instance(), instance(), instance()) }
    bindSingleton { GigaAuth(instance()) }
    bindSingleton<GigaRestChatAPI> { GigaRestChatAPI(instance(), instance(), instance()) }
    bindSingleton<QwenChatAPI> { QwenChatAPI(instance(), instance()) }
    bindSingleton<AiTunnelChatAPI> { AiTunnelChatAPI(instance(), instance()) }
    bindSingleton<AnthropicChatAPI> { AnthropicChatAPI(instance(), instance()) }
    bindSingleton<OpenAIChatAPI> { OpenAIChatAPI(instance(), instance()) }
    bindSingleton { OpenAIImageGenerationGateway(instance()) }
    bindSingleton<LocalChatAPI> { LocalChatAPI(instance()) }
    bindSingleton { CodexOAuthService(instance()) }
    bindSingleton<CodexChatAPI> { CodexChatAPI(instance(), instance(), instance()) }
    bindSingleton { OpenAIVisionGateway(instance(), instance()) }
    bindSingleton { AnthropicVisionGateway(instance(), instance()) }
    bindSingleton { LocalVisionGateway(instance(), instance()) }
    bindSingleton {
        LLMFactory(
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
    bindSingleton<LLMChatAPI> { instance<LLMFactory>() }
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
