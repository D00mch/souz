package ru.souz.backend

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.souz.db.ConfigStore
import ru.souz.db.SettingsProviderImpl
import ru.souz.llms.SessionTokenLogging
import ru.souz.llms.local.LocalBridgeLoader
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.local.LocalHostInfoProvider
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalNativeBridge
import ru.souz.llms.local.LocalPromptRenderer
import ru.souz.llms.local.LocalProviderAvailability
import ru.souz.llms.local.LocalStrictJsonParser
import ru.souz.llms.runtime.LLMFactory
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.giga.GigaAuth
import ru.souz.llms.giga.GigaRestChatAPI
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.qwen.QwenChatAPI
import ru.souz.llms.tunnel.AiTunnelChatAPI

class BackendRuntime private constructor(
    val chatService: ChatService,
    private val settingsProvider: SettingsProviderImpl,
    private val localRuntime: LocalLlamaRuntime,
) : AutoCloseable {
    fun selectedModel(): String = settingsProvider.gigaModel.alias

    override fun close() {
        localRuntime.close()
    }

    companion object {
        fun create(): BackendRuntime {
            val logObjectMapper = jacksonObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT)

            val hostInfoProvider = LocalHostInfoProvider()
            val modelStore = LocalModelStore()
            val bridgeLoader = LocalBridgeLoader(hostInfoProvider)
            val localAvailability = LocalProviderAvailability(hostInfoProvider, modelStore, bridgeLoader)
            val settingsProvider = SettingsProviderImpl(ConfigStore, localAvailability)
            val tokenLogging = SessionTokenLogging(logObjectMapper)

            val localRuntime = LocalLlamaRuntime(
                availability = localAvailability,
                modelStore = modelStore,
                promptRenderer = LocalPromptRenderer(),
                strictJsonParser = LocalStrictJsonParser(),
                bridge = LocalNativeBridge(bridgeLoader),
            )
            val chatApi = LLMFactory(
                settingsProvider = settingsProvider,
                restApi = GigaRestChatAPI(GigaAuth(settingsProvider), settingsProvider, tokenLogging),
                qwenApi = QwenChatAPI(settingsProvider, tokenLogging),
                aiTunnelApi = AiTunnelChatAPI(settingsProvider, tokenLogging),
                anthropicApi = AnthropicChatAPI(settingsProvider, tokenLogging),
                openAiApi = OpenAIChatAPI(settingsProvider, tokenLogging),
                localApi = LocalChatAPI(localRuntime),
            )
            val service = ChatService(
                chatApi = chatApi,
                settings = {
                    BackendChatSettings(
                        model = settingsProvider.gigaModel.alias,
                        provider = settingsProvider.gigaModel.provider,
                        temperature = settingsProvider.temperature,
                        contextSize = settingsProvider.contextSize,
                    )
                },
                systemPrompt = backendSystemPrompt(),
                tokenLogging = tokenLogging,
            )

            return BackendRuntime(
                chatService = service,
                settingsProvider = settingsProvider,
                localRuntime = localRuntime,
            )
        }

        private fun backendSystemPrompt(): String =
            System.getenv("SOUZ_BACKEND_SYSTEM_PROMPT")
                ?: System.getProperty("souz.backend.systemPrompt")
                ?: "You are Souz AI backend assistant. Answer directly and concisely in the user's language."
    }
}
