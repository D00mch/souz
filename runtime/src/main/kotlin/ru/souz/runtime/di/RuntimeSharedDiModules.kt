package ru.souz.runtime.di

import com.fasterxml.jackson.databind.ObjectMapper
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
import ru.souz.skill.ClawHubClient
import ru.souz.skill.ClawHubManager
import ru.souz.skill.FilesystemSkillCatalog
import ru.souz.skill.HttpClawHubClient
import ru.souz.skill.SkillDirectories

fun runtimeCoreDiModule(): DI.Module = DI.Module("runtimeCore") {
    bindSingleton { ConfigStore }
    bindSingleton { SkillDirectories.default() }
    bindSingleton { FilesystemSkillCatalog(instance()) }
    bindSingleton<ClawHubClient> { HttpClawHubClient() }
    bindSingleton { ClawHubManager(directories = instance(), client = instance()) }
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
    bindSingleton<LocalChatAPI> { LocalChatAPI(instance()) }
    bindSingleton { LLMFactory(instance(), instance(), instance(), instance(), instance(), instance(), instance()) }
    bindSingleton<LLMChatAPI> { instance<LLMFactory>() }
}
