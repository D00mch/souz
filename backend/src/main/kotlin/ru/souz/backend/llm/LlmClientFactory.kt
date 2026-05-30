package ru.souz.backend.llm

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.agent.AgentId
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel

data class BackendLlmExecutionContext(
    val userId: String,
    val executionId: String,
    val settingsProvider: SettingsProvider,
)

data class SharedProviderTransport(
    val id: String,
)

interface ProviderChatApiBuilder {
    fun build(
        provider: LlmProvider,
        settingsProvider: SettingsProvider,
        sharedTransport: SharedProviderTransport,
        executionContext: BackendLlmExecutionContext,
    ): LLMChatAPI
}

interface LlmClientFactory {
    suspend fun create(context: BackendLlmExecutionContext): LLMChatAPI
}

class BackendLlmClientFactory(
    private val credentialResolver: ProviderCredentialResolver,
    private val providerClientFactory: ProviderChatApiBuilder,
    private val localChatApi: LLMChatAPI,
) : LlmClientFactory {
    private val transports = LlmProvider.entries.associateWith { provider ->
        SharedProviderTransport(id = provider.name.lowercase())
    }

    override suspend fun create(context: BackendLlmExecutionContext): LLMChatAPI =
        RoutingLlmChatApi(
            context = context,
            credentialResolver = credentialResolver,
            providerClientFactory = providerClientFactory,
            localChatApi = localChatApi,
            transports = transports,
        )
}

private class RoutingLlmChatApi(
    private val context: BackendLlmExecutionContext,
    private val credentialResolver: ProviderCredentialResolver,
    private val providerClientFactory: ProviderChatApiBuilder,
    private val localChatApi: LLMChatAPI,
    private val transports: Map<LlmProvider, SharedProviderTransport>,
) : LLMChatAPI {
    private val mutex = Mutex()
    private val apis = LinkedHashMap<LlmProvider, LLMChatAPI>()

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        apiFor(providerFor(body.model)).message(body)

    override suspend fun messageStream(body: LLMRequest.Chat) =
        apiFor(providerFor(body.model)).messageStream(body)

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        apiFor(context.settingsProvider.embeddingsModel.provider).embeddings(body)

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        apiFor(context.settingsProvider.gigaModel.provider).uploadFile(file)

    override suspend fun downloadFile(fileId: String): String? =
        apiFor(context.settingsProvider.gigaModel.provider).downloadFile(fileId)

    override suspend fun balance(): LLMResponse.Balance =
        apiFor(context.settingsProvider.gigaModel.provider).balance()

    private suspend fun apiFor(provider: LlmProvider): LLMChatAPI {
        if (provider == LlmProvider.LOCAL) {
            return localChatApi
        }
        apis[provider]?.let { return it }
        return mutex.withLock {
            apis[provider]?.let { return@withLock it }
            val credential = credentialResolver.resolve(context.userId, provider)
                ?: error("Missing configured credential for provider $provider.")
            providerClientFactory.build(
                provider = provider,
                settingsProvider = CredentialOverrideSettingsProvider(
                    delegate = context.settingsProvider,
                    overrideProvider = provider,
                    apiKey = credential.apiKey,
                ),
                sharedTransport = transports.getValue(provider),
                executionContext = context,
            ).also { api ->
                apis[provider] = api
            }
        }
    }

    private fun providerFor(model: String): LlmProvider =
        LLMModel.entries.firstOrNull { candidate ->
            candidate.alias.equals(model, ignoreCase = true) || candidate.name.equals(model, ignoreCase = true)
        }?.provider ?: context.settingsProvider.gigaModel.provider
}

private class CredentialOverrideSettingsProvider(
    private val delegate: SettingsProvider,
    private val overrideProvider: LlmProvider,
    private val apiKey: String,
) : SettingsProvider by delegate {
    override var gigaChatKey: String?
        get() = if (overrideProvider == LlmProvider.GIGA) apiKey else delegate.gigaChatKey
        set(value) {
            delegate.gigaChatKey = value
        }

    override var qwenChatKey: String?
        get() = if (overrideProvider == LlmProvider.QWEN) apiKey else delegate.qwenChatKey
        set(value) {
            delegate.qwenChatKey = value
        }

    override var aiTunnelKey: String?
        get() = if (overrideProvider == LlmProvider.AI_TUNNEL) apiKey else delegate.aiTunnelKey
        set(value) {
            delegate.aiTunnelKey = value
        }

    override var anthropicKey: String?
        get() = if (overrideProvider == LlmProvider.ANTHROPIC) apiKey else delegate.anthropicKey
        set(value) {
            delegate.anthropicKey = value
        }

    override var openaiKey: String?
        get() = if (overrideProvider == LlmProvider.OPENAI) apiKey else delegate.openaiKey
        set(value) {
            delegate.openaiKey = value
        }

    override var saluteSpeechKey: String?
        get() = delegate.saluteSpeechKey
        set(value) {
            delegate.saluteSpeechKey = value
        }

    override var supportEmail: String?
        get() = delegate.supportEmail
        set(value) {
            delegate.supportEmail = value
        }

    override var defaultCalendar: String?
        get() = delegate.defaultCalendar
        set(value) {
            delegate.defaultCalendar = value
        }

    override var regionProfile: String
        get() = delegate.regionProfile
        set(value) {
            delegate.regionProfile = value
        }

    override var activeAgentId: AgentId
        get() = delegate.activeAgentId
        set(value) {
            delegate.activeAgentId = value
        }

    override var gigaModel: LLMModel
        get() = delegate.gigaModel
        set(value) {
            delegate.gigaModel = value
        }

    override var useFewShotExamples: Boolean
        get() = delegate.useFewShotExamples
        set(value) {
            delegate.useFewShotExamples = value
        }

    override var useStreaming: Boolean
        get() = delegate.useStreaming
        set(value) {
            delegate.useStreaming = value
        }

    override var notificationSoundEnabled: Boolean
        get() = delegate.notificationSoundEnabled
        set(value) {
            delegate.notificationSoundEnabled = value
        }

    override var voiceInputReviewEnabled: Boolean
        get() = delegate.voiceInputReviewEnabled
        set(value) {
            delegate.voiceInputReviewEnabled = value
        }

    override var safeModeEnabled: Boolean
        get() = delegate.safeModeEnabled
        set(value) {
            delegate.safeModeEnabled = value
        }

    override var needsOnboarding: Boolean
        get() = delegate.needsOnboarding
        set(value) {
            delegate.needsOnboarding = value
        }

    override var onboardingCompleted: Boolean
        get() = delegate.onboardingCompleted
        set(value) {
            delegate.onboardingCompleted = value
        }

    override var requestTimeoutMillis: Long
        get() = delegate.requestTimeoutMillis
        set(value) {
            delegate.requestTimeoutMillis = value
        }

    override var contextSize: Int
        get() = delegate.contextSize
        set(value) {
            delegate.contextSize = value
        }

    override var initialWindowWidthDp: Int
        get() = delegate.initialWindowWidthDp
        set(value) {
            delegate.initialWindowWidthDp = value
        }

    override var initialWindowHeightDp: Int
        get() = delegate.initialWindowHeightDp
        set(value) {
            delegate.initialWindowHeightDp = value
        }

    override var temperature: Float
        get() = delegate.temperature
        set(value) {
            delegate.temperature = value
        }

    override var forbiddenFolders: List<String>
        get() = delegate.forbiddenFolders
        set(value) {
            delegate.forbiddenFolders = value
        }

    override var embeddingsModel: EmbeddingsModel
        get() = delegate.embeddingsModel
        set(value) {
            delegate.embeddingsModel = value
        }

    override var voiceRecognitionModel: VoiceRecognitionModel
        get() = delegate.voiceRecognitionModel
        set(value) {
            delegate.voiceRecognitionModel = value
        }

    override var mcpServersJson: String?
        get() = delegate.mcpServersJson
        set(value) {
            delegate.mcpServersJson = value
        }

    override var mcpServersFile: String?
        get() = delegate.mcpServersFile
        set(value) {
            delegate.mcpServersFile = value
        }
}
