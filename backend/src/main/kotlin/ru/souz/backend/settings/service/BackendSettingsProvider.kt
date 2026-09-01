package ru.souz.backend.settings.service

import ru.souz.agent.AgentId
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.config.SystemBackendConfigSource
import ru.souz.backend.settings.repository.BackendServerPreferenceStore
import ru.souz.db.DEFAULT_REQUEST_TIMEOUT_MILLIS
import ru.souz.db.REGION_EN
import ru.souz.db.REGION_RU
import ru.souz.db.SettingsProvider
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmProvider
import ru.souz.llms.LocalModelAvailability
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.findLLMModel
import ru.souz.llms.local.LocalEmbeddingProfiles

class BackendSettingsProvider(
    private val preferenceStore: BackendServerPreferenceStore,
    private val localProviderAvailability: LocalModelAvailability,
    private val source: BackendConfigSource = SystemBackendConfigSource,
) : SettingsProvider {
    private val promptOverrides = mutableMapOf<Pair<AgentId, LLMModel>, String>()
    private val llmBuildProfile by lazy { LlmBuildProfile(this, localProviderAvailability) }

    override var gigaChatKey: String? = configured("GIGA_KEY")
    override var qwenChatKey: String? = configured("QWEN_KEY")
    override var aiTunnelKey: String? = configured("AITUNNEL_KEY")
    override var anthropicKey: String? = configured("ANTHROPIC_API_KEY")
    override var openaiKey: String? = configured("OPENAI_API_KEY")
    override var openaiBaseUrl: String? = configured(OPENAI_BASE_URL)
    override var openaiModel: String? = configured(OPENAI_MODEL)
    override val openaiSummarizationApiKey: String? = configured("OPENAI_SUMMARIZATION_API_KEY")
    override val openaiSummarizationBaseUrl: String? = configured("OPENAI_SUMMARIZATION_BASE_URL")
    override val openaiSummarizationModel: String? = configured("OPENAI_SUMMARIZATION_MODEL")
    override val openaiSummarizationParameters: String? = configured("OPENAI_SUMMARIZATION_PARAMETERS")
    override val summarizationContextSize: Int? = openaiSummarizationModel
        ?.let { configured("OPENAI_SUMMARIZATION_CONTEXT_SIZE")?.toIntOrNull() }
        ?.takeIf { it > 0 }
    override var saluteSpeechKey: String? = configured("VOICE_KEY")
    override var supportEmail: String? = configured(SUPPORT_EMAIL)
    override var defaultCalendar: String? = configured(DEFAULT_CALENDAR)
    override var regionProfile: String = normalizeRegion(configured(APP_LANGUAGE))
    override var activeAgentId: AgentId = AgentId.fromStorageValue(configured(ACTIVE_AGENT_ID))
    override var ambientAnalysisModel: LLMModel = configured(AMBIENT_ANALYSIS_MODEL)
        ?.let(::findLLMModel)
        ?.let(llmBuildProfile::normalizeAmbientAnalysisModel)
        ?: llmBuildProfile.defaultAmbientAnalysisModel()
    override var useFewShotExamples: Boolean = configured(USE_FEW_SHOTS)?.toBooleanStrictOrNull() ?: false
    override var useStreaming: Boolean = configured(USE_STREAMING)?.toBooleanStrictOrNull()
        ?: configured(USE_GRPC_LEGACY)?.toBooleanStrictOrNull()
        ?: false
    override var notificationSoundEnabled: Boolean =
        configured(NOTIFICATION_SOUND_ENABLED)?.toBooleanStrictOrNull() ?: true
    override var voiceInputReviewEnabled: Boolean =
        configured(VOICE_INPUT_REVIEW_ENABLED)?.toBooleanStrictOrNull() ?: false
    override var safeModeEnabled: Boolean = configured(SAFE_MODE_ENABLED)?.toBooleanStrictOrNull() ?: true
    override var needsOnboarding: Boolean = configured(NEEDS_ONBOARDING)?.toBooleanStrictOrNull() ?: false
    override var onboardingCompleted: Boolean = configured(ONBOARDING_COMPLETED)?.toBooleanStrictOrNull() ?: false
    override var requestTimeoutMillis: Long =
        configured(REQUEST_TIMEOUT_MILLIS)?.toLongOrNull() ?: DEFAULT_REQUEST_TIMEOUT_MILLIS
    override var contextSize: Int =
        configured(CONTEXT_SIZE)?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS
    override var initialWindowWidthDp: Int = configured(INITIAL_WINDOW_WIDTH_DP)?.toIntOrNull() ?: 580
    override var initialWindowHeightDp: Int = configured(INITIAL_WINDOW_HEIGHT_DP)?.toIntOrNull() ?: 780
    override var temperature: Float = configured(TEMPERATURE)?.toFloatOrNull() ?: 0.7f
    override var forbiddenFolders: List<String> = configured(FORBIDDEN_FOLDERS)
        ?.lines()
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?: DEFAULT_FORBIDDEN_FOLDERS
    private var configuredEmbeddingsModel: EmbeddingsModel = configured(EMBEDDINGS_MODEL)
        ?.let { value ->
            EmbeddingsModel.entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.alias.equals(value, ignoreCase = true)
            }
        }
        ?: EmbeddingsModel.GigaEmbeddings
    override var voiceRecognitionModel: VoiceRecognitionModel = configured(VOICE_RECOGNITION_MODEL)
        ?.let { value ->
            VoiceRecognitionModel.entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.alias.equals(value, ignoreCase = true)
            }
        }
        ?: VoiceRecognitionModel.SaluteSpeech
    override var mcpServersJson: String? = configured(MCP_SERVERS_JSON)
    override var mcpServersFile: String? = configured(MCP_SERVERS_FILE)

    override var codexAccessToken: String?
        get() = storedOrConfigured(CODEX_ACCESS_TOKEN)
        set(value) = putOrTombstone(CODEX_ACCESS_TOKEN, value)

    override var codexRefreshToken: String?
        get() = storedOrConfigured(CODEX_REFRESH_TOKEN)
        set(value) = putOrTombstone(CODEX_REFRESH_TOKEN, value)

    override var codexAccountId: String?
        get() = storedOrConfigured(CODEX_ACCOUNT_ID)
        set(value) = putOrTombstone(CODEX_ACCOUNT_ID, value)

    override var codexExpiresAt: Long?
        get() = storedOrConfigured(CODEX_EXPIRES_AT)?.toLongOrNull()
        set(value) = putOrTombstone(CODEX_EXPIRES_AT, value?.toString())

    override var gigaModel: LLMModel = configured(GIGA_MODEL)
        ?.let(::findLLMModel)
        ?.let { model -> llmBuildProfile.normalizeConfiguredModel(model, openaiModel) { defaultLlmModel() } }
        ?: defaultLlmModel()
        set(value) {
            field = llmBuildProfile.normalizeConfiguredModel(value, openaiModel) { defaultLlmModel() }
        }

    override var embeddingsModel: EmbeddingsModel
        get() = enforcedEmbeddingsModel() ?: configuredEmbeddingsModel
        set(value) {
            configuredEmbeddingsModel = value
        }

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String? {
        val key = agentId to model
        return promptOverrides[key] ?: configured("${SYSTEM_PROMPT}_${agentId.storageValue}_${model.name}")
    }

    override fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?) {
        val key = agentId to model
        if (prompt.isNullOrBlank()) {
            promptOverrides.remove(key)
        } else {
            promptOverrides[key] = prompt
        }
    }

    override fun hasKey(provider: LlmProvider): Boolean = when (provider) {
        LlmProvider.GIGA -> !gigaChatKey.isNullOrBlank()
        LlmProvider.QWEN -> !qwenChatKey.isNullOrBlank()
        LlmProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
        LlmProvider.ANTHROPIC -> !anthropicKey.isNullOrBlank()
        LlmProvider.OPENAI -> !openaiKey.isNullOrBlank()
        LlmProvider.LOCAL -> true
        LlmProvider.CODEX -> !codexAccessToken.isNullOrBlank()
    }

    override fun hasKey(provider: VoiceRecognitionProvider): Boolean = when (provider) {
        VoiceRecognitionProvider.SALUTE_SPEECH -> !saluteSpeechKey.isNullOrBlank()
        VoiceRecognitionProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
        VoiceRecognitionProvider.OPENAI -> !openaiKey.isNullOrBlank()
        VoiceRecognitionProvider.LOCAL_MACOS -> true
    }

    private fun configured(envKey: String, propertyKey: String = envKey): String? =
        source.value(envKey = envKey, propertyKey = propertyKey)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    // An absent row falls back to deploy config; an empty row is a persisted tombstone.
    private fun storedOrConfigured(key: String): String? =
        when (val stored = preferenceStore.get(key)) {
            null -> configured(key)
            "" -> null
            else -> stored
        }

    private fun putOrTombstone(key: String, value: String?) {
        preferenceStore.put(key, value ?: "")
    }

    private fun normalizeRegion(value: String?): String =
        if (value?.lowercase() == REGION_EN) REGION_EN else REGION_RU

    private fun defaultLlmModel(): LLMModel = llmBuildProfile.defaultConfiguredModel(::hasKey)

    private fun enforcedEmbeddingsModel(): EmbeddingsModel? = when {
        gigaModel.provider == LlmProvider.LOCAL && localProviderAvailability.isProviderAvailable() ->
            LocalEmbeddingProfiles.default().embeddingsModel
        else -> null
    }

    private companion object {
        const val OPENAI_BASE_URL = "OPENAI_BASE_URL"
        const val OPENAI_MODEL = "OPENAI_MODEL"
        const val CODEX_ACCESS_TOKEN = "CODEX_ACCESS_TOKEN"
        const val CODEX_REFRESH_TOKEN = "CODEX_REFRESH_TOKEN"
        const val CODEX_ACCOUNT_ID = "CODEX_ACCOUNT_ID"
        const val CODEX_EXPIRES_AT = "CODEX_EXPIRES_AT"
        const val APP_LANGUAGE = "APP_LANGUAGE"
        const val USE_FEW_SHOTS = "USE_FEW_SHOTS"
        const val USE_STREAMING = "USE_STREAMING"
        const val NOTIFICATION_SOUND_ENABLED = "NOTIFICATION_SOUND_ENABLED"
        const val VOICE_INPUT_REVIEW_ENABLED = "VOICE_INPUT_REVIEW_ENABLED"
        const val SAFE_MODE_ENABLED = "SAFE_MODE_ENABLED"
        const val USE_GRPC_LEGACY = "USE_GRPC"
        const val SUPPORT_EMAIL = "SUPPORT_EMAIL"
        const val SYSTEM_PROMPT = "SYSTEM_PROMPT"
        const val ACTIVE_AGENT_ID = "ACTIVE_AGENT_ID"
        const val DEFAULT_CALENDAR = "DEFAULT_CALENDAR"
        const val GIGA_MODEL = "GIGA_MODEL"
        const val AMBIENT_ANALYSIS_MODEL = "AMBIENT_ANALYSIS_MODEL"
        const val NEEDS_ONBOARDING = "NEEDS_ONBOARDING"
        const val ONBOARDING_COMPLETED = "ONBOARDING_COMPLETED"
        const val REQUEST_TIMEOUT_MILLIS = "REQUEST_TIMEOUT_MILLIS"
        const val CONTEXT_SIZE = "CONTEXT_SIZE"
        const val INITIAL_WINDOW_WIDTH_DP = "INITIAL_WINDOW_WIDTH_DP"
        const val INITIAL_WINDOW_HEIGHT_DP = "INITIAL_WINDOW_HEIGHT_DP"
        const val TEMPERATURE = "TEMPERATURE"
        const val FORBIDDEN_FOLDERS = "FORBIDDEN_FOLDERS"
        const val EMBEDDINGS_MODEL = "EMBEDDINGS_MODEL"
        const val VOICE_RECOGNITION_MODEL = "VOICE_RECOGNITION_MODEL"
        const val MCP_SERVERS_JSON = "MCP_SERVERS_JSON"
        const val MCP_SERVERS_FILE = "MCP_SERVERS_FILE"
        val DEFAULT_FORBIDDEN_FOLDERS = listOf(
            "~/Library/",
            "~/.bash_history",
            "~/.zsh_history",
            "~/.zprofile",
            "~/.zshenv",
            "~/.bashprofile",
            "~/.ssh",
        )
    }
}
