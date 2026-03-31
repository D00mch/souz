package ru.souz.db

import ru.souz.agent.AgentId
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmBuildProfile
import ru.souz.llms.LlmBuildProfileSettings
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.llms.VoiceRecognitionProvider
import ru.souz.llms.local.LocalBridgeLoader
import ru.souz.llms.local.LocalHostInfoProvider
import ru.souz.llms.local.LocalModelStore
import ru.souz.llms.local.LocalProviderAvailability
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

interface SettingsProvider : AgentSettingsProvider, LlmBuildProfileSettings {
    var gigaChatKey: String?
    var qwenChatKey: String?
    var aiTunnelKey: String?
    var anthropicKey: String?
    var openaiKey: String?
    var saluteSpeechKey: String?
    var supportEmail: String?
    override var defaultCalendar: String?
    override var regionProfile: String
    override var activeAgentId: AgentId
    override var gigaModel: LLMModel
    var useFewShotExamples: Boolean
    override var useStreaming: Boolean
    var notificationSoundEnabled: Boolean
    var voiceInputReviewEnabled: Boolean
    var safeModeEnabled: Boolean
    var needsOnboarding: Boolean
    var onboardingCompleted: Boolean
    var requestTimeoutMillis: Long
    override var contextSize: Int
    var initialWindowWidthDp: Int
    var initialWindowHeightDp: Int
    override var temperature: Float
    var forbiddenFolders: List<String>
    var embeddingsModel: EmbeddingsModel
    var voiceRecognitionModel: VoiceRecognitionModel
    var mcpServersJson: String?
    var mcpServersFile: String?
}

class SettingsProviderImpl(
    private val configStore: ConfigStore,
    private val localProviderAvailability: LocalProviderAvailability = defaultLocalProviderAvailability(),
) : SettingsProvider {

    private var _fewShotsDelegate: String? by keyDelegate(configKey = USE_FEW_SHOTS, envKey = USE_FEW_SHOTS)
    private var _appLanguageDelegate: String?
        get() = configStore.get(APP_LANGUAGE)
        set(value) {
            when (value) {
                null, "" -> configStore.rm(APP_LANGUAGE)
                else -> configStore.put(APP_LANGUAGE, value)
            }
        }
    private var _gigaModelDelegate: String? by keyDelegate(configKey = GIGA_MODEL, envKey = GIGA_MODEL)
    private var _useStreamingDelegate: String? by keyDelegate(configKey = USE_STREAMING, envKey = USE_STREAMING)
    private var _notificationSoundEnabledDelegate: String? by keyDelegate(
        configKey = NOTIFICATION_SOUND_ENABLED,
        envKey = NOTIFICATION_SOUND_ENABLED
    )
    private var _voiceInputReviewEnabledDelegate: String? by keyDelegate(
        configKey = VOICE_INPUT_REVIEW_ENABLED,
        envKey = VOICE_INPUT_REVIEW_ENABLED
    )
    private var _safeModeDelegate: String? by keyDelegate(configKey = SAFE_MODE_ENABLED, envKey = SAFE_MODE_ENABLED)
    private var _requestTimeoutDelegate: String? by keyDelegate(
        configKey = REQUEST_TIMEOUT_MILLIS,
        envKey = REQUEST_TIMEOUT_MILLIS
    )
    private var _contextSizeDelegate: String? by keyDelegate(
        configKey = CONTEXT_SIZE,
        envKey = CONTEXT_SIZE
    )
    private var _initialWindowWidthDelegate: String? by keyDelegate(
        configKey = INITIAL_WINDOW_WIDTH_DP,
        envKey = INITIAL_WINDOW_WIDTH_DP
    )
    private var _initialWindowHeightDelegate: String? by keyDelegate(
        configKey = INITIAL_WINDOW_HEIGHT_DP,
        envKey = INITIAL_WINDOW_HEIGHT_DP
    )
    private var _temperatureDelegate: String? by keyDelegate(
        configKey = TEMPERATURE,
        envKey = TEMPERATURE
    )
    private var _forbiddenFoldersDelegate: String? by keyDelegate(
        configKey = FORBIDDEN_FOLDERS,
        envKey = FORBIDDEN_FOLDERS
    )
    private var _needsOnboardingDelegate: String? by keyDelegate(
        configKey = NEEDS_ONBOARDING,
        envKey = NEEDS_ONBOARDING
    )
    private var _onboardingCompletedDelegate: String? by keyDelegate(
        configKey = ONBOARDING_COMPLETED,
        envKey = ONBOARDING_COMPLETED
    )
    private var _embeddingsModelDelegate: String? by keyDelegate(
        configKey = EMBEDDINGS_MODEL,
        envKey = EMBEDDINGS_MODEL
    )
    private var _voiceRecognitionModelDelegate: String? by keyDelegate(
        configKey = VOICE_RECOGNITION_MODEL,
        envKey = VOICE_RECOGNITION_MODEL
    )

    init {
        // apply defaults
        if (_notificationSoundEnabledDelegate.isNullOrBlank()) _notificationSoundEnabledDelegate = "true"
        if (_safeModeDelegate.isNullOrBlank()) _safeModeDelegate = "true"
    }

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String? {
        val key = "${SYSTEM_PROMPT}_${agentId.storageValue}_${model.name}"
        return configStore.get<String>(key)
    }

    override fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?) {
        val key = "${SYSTEM_PROMPT}_${agentId.storageValue}_${model.name}"
        when {
            prompt.isNullOrBlank() -> configStore.rm(key)
            else -> configStore.put(key, prompt)
        }
    }

    override var gigaChatKey: String? by keyDelegate(configKey = GIGA_CHAT_KEY, envKey = "GIGA_KEY")
    override var qwenChatKey: String? by keyDelegate(configKey = QWEN_CHAT_KEY, envKey = "QWEN_KEY")
    override var aiTunnelKey: String? by keyDelegate(configKey = AI_TUNNEL_KEY, envKey = "AITUNNEL_KEY")
    override var anthropicKey: String? by keyDelegate(configKey = ANTHROPIC_KEY, envKey = "ANTHROPIC_API_KEY")
    override var openaiKey: String? by keyDelegate(configKey = OPENAI_KEY, envKey = "OPENAI_API_KEY")
    override var saluteSpeechKey: String? by keyDelegate(configKey = SALUTE_SPEECH_KEY, envKey = "VOICE_KEY")
    override var supportEmail: String? by keyDelegate(configKey = SUPPORT_EMAIL, envKey = SUPPORT_EMAIL)
    override var defaultCalendar: String? by keyDelegate(configKey = DEFAULT_CALENDAR, envKey = DEFAULT_CALENDAR)
    override var regionProfile: String
        get() {
            val raw = _appLanguageDelegate?.trim()?.lowercase()
            val normalized = if (raw == REGION_EN) REGION_EN else REGION_RU
            if (raw != normalized) {
                _appLanguageDelegate = normalized
            }
            return normalized
        }
        set(value) {
            _appLanguageDelegate = if (value.trim().lowercase() == REGION_EN) REGION_EN else REGION_RU
        }

    override var activeAgentId: AgentId
        get() = AgentId.fromStorageValue(configStore.get<String>(ACTIVE_AGENT_ID))
        set(value) {
            configStore.put(ACTIVE_AGENT_ID, value.storageValue)
        }

    override var gigaModel: LLMModel
        get() = _gigaModelDelegate?.let { value ->
            LLMModel.entries.firstOrNull { model ->
                model.name.equals(value, ignoreCase = true) || model.alias.equals(value, ignoreCase = true)
            }
        }?.let(::normalizeGigaModel)
            ?: defaultLlmModel()
        set(value) {
            _gigaModelDelegate = normalizeGigaModel(value).alias
        }

    override var useFewShotExamples: Boolean
        get() = _fewShotsDelegate?.lowercase() == "true"
        set(value) {
            _fewShotsDelegate = value.toString()
        }

    override var useStreaming: Boolean
        get() {
            val value = _useStreamingDelegate
                ?: configStore.get(USE_GRPC_LEGACY)
                ?: System.getenv(USE_GRPC_LEGACY)
                ?: System.getProperty(USE_GRPC_LEGACY)
            return value?.lowercase() == "true"
        }
        set(value) {
            _useStreamingDelegate = value.toString()
        }

    override var notificationSoundEnabled: Boolean
        get() = _notificationSoundEnabledDelegate?.toBooleanStrictOrNull() ?: true
        set(value) {
            _notificationSoundEnabledDelegate = value.toString()
        }

    override var voiceInputReviewEnabled: Boolean
        get() = _voiceInputReviewEnabledDelegate?.toBooleanStrictOrNull() ?: false
        set(value) {
            _voiceInputReviewEnabledDelegate = value.toString()
        }

    override var safeModeEnabled: Boolean
        get() = _safeModeDelegate?.lowercase() == "true"
        set(value) {
            _safeModeDelegate = value.toString()
        }

    override var needsOnboarding: Boolean
        get() = _needsOnboardingDelegate?.toBooleanStrictOrNull() ?: false
        set(value) {
            _needsOnboardingDelegate = value.toString()
        }

    override var onboardingCompleted: Boolean
        get() = _onboardingCompletedDelegate?.toBooleanStrictOrNull() ?: false
        set(value) {
            _onboardingCompletedDelegate = value.toString()
        }

    override var requestTimeoutMillis: Long
        get() = _requestTimeoutDelegate?.toLongOrNull() ?: 40_000L
        set(value) {
            _requestTimeoutDelegate = value.toString()
        }

    override var contextSize: Int
        get() = _contextSizeDelegate?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS
        set(value) {
            _contextSizeDelegate = (value.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS).toString()
        }

    override var initialWindowWidthDp: Int
        get() = _initialWindowWidthDelegate?.toIntOrNull() ?: 580
        set(value) {
            _initialWindowWidthDelegate = value.toString()
        }

    override var initialWindowHeightDp: Int
        get() = _initialWindowHeightDelegate?.toIntOrNull() ?: 780
        set(value) {
            _initialWindowHeightDelegate = value.toString()
        }

    override var temperature: Float
        get() = _temperatureDelegate?.toFloatOrNull() ?: 0.7f
        set(value) {
            _temperatureDelegate = value.toString()
        }

    override var forbiddenFolders: List<String>
        get() = _forbiddenFoldersDelegate
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: DEFAULT_FORBIDDEN_FOLDERS
        set(value) {
            _forbiddenFoldersDelegate = value
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

    override var embeddingsModel: EmbeddingsModel
        get() = _embeddingsModelDelegate?.let { value ->
            EmbeddingsModel.entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.alias.equals(
                    value,
                    ignoreCase = true
                )
            }
        } ?: EmbeddingsModel.GigaEmbeddings
        set(value) {
            _embeddingsModelDelegate = value.name
        }

    override var voiceRecognitionModel: VoiceRecognitionModel
        get() = _voiceRecognitionModelDelegate?.let { value ->
            VoiceRecognitionModel.entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.alias.equals(
                    value,
                    ignoreCase = true
                )
            }
        } ?: VoiceRecognitionModel.SaluteSpeech
        set(value) {
            _voiceRecognitionModelDelegate = value.name
        }

    override var mcpServersJson: String? by keyDelegate(
        configKey = MCP_SERVERS_JSON,
        envKey = MCP_SERVERS_JSON
    )

    override var mcpServersFile: String? by keyDelegate(
        configKey = MCP_SERVERS_FILE,
        envKey = MCP_SERVERS_FILE
    )

    private fun defaultLlmModel(): LLMModel {
        val defaults = LlmBuildProfile.defaultsForLanguage(regionProfile)
        val availableLocalDefault = localProviderAvailability.defaultGigaModel()
        return LlmBuildProfile.providerPrioritiesForLanguage(regionProfile)
            .firstNotNullOfOrNull { provider ->
                when (provider) {
                    LlmProvider.LOCAL -> availableLocalDefault
                    else -> defaults[provider]?.takeIf { hasConfiguredAccess(it.provider) }
                }
            }
            ?: availableLocalDefault
            ?: defaults.values.first()
    }

    private fun normalizeGigaModel(model: LLMModel): LLMModel {
        val availableProviders = LlmBuildProfile.defaultsForLanguage(regionProfile).keys
        return when {
            model.provider == LlmProvider.LOCAL && model !in localProviderAvailability.availableGigaModels() -> defaultLlmModel()
            model.provider !in availableProviders -> defaultLlmModel()
            else -> model
        }
    }

    private fun hasConfiguredAccess(provider: LlmProvider): Boolean = when (provider) {
        LlmProvider.GIGA -> !gigaChatKey.isNullOrBlank()
        LlmProvider.QWEN -> !qwenChatKey.isNullOrBlank()
        LlmProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
        LlmProvider.ANTHROPIC -> !anthropicKey.isNullOrBlank()
        LlmProvider.OPENAI -> !openaiKey.isNullOrBlank()
        LlmProvider.LOCAL -> localProviderAvailability.isProviderAvailable()
    }

    private fun keyDelegate(configKey: String, envKey: String, sysPropKey: String = envKey) =
        object : ReadWriteProperty<Any?, String?> {

            override fun getValue(thisRef: Any?, property: KProperty<*>): String? =
                configStore.get(configKey)
                    ?: System.getenv(envKey)
                    ?: System.getProperty(sysPropKey)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
                when (value) {
                    null, "" -> configStore.rm(configKey)
                    else -> configStore.put(configKey, value)
                }
            }
        }

    companion object {
        const val GIGA_CHAT_KEY = "GIGA_CHAT_KEY"
        const val QWEN_CHAT_KEY = "QWEN_CHAT_KEY"
        const val AI_TUNNEL_KEY = "AI_TUNNEL_KEY"
        const val ANTHROPIC_KEY = "ANTHROPIC_KEY"
        const val OPENAI_KEY = "OPENAI_KEY"
        const val SALUTE_SPEECH_KEY = "SALUTE_SPEECH_KEY"
        const val APP_LANGUAGE = "APP_LANGUAGE"
        private const val USE_FEW_SHOTS = "USE_FEW_SHOTS"
        private const val USE_STREAMING = "USE_STREAMING"
        private const val NOTIFICATION_SOUND_ENABLED = "NOTIFICATION_SOUND_ENABLED"
        private const val VOICE_INPUT_REVIEW_ENABLED = "VOICE_INPUT_REVIEW_ENABLED"
        private const val SAFE_MODE_ENABLED = "SAFE_MODE_ENABLED"
        private const val USE_GRPC_LEGACY = "USE_GRPC"
        private const val SUPPORT_EMAIL = "SUPPORT_EMAIL"
        private const val SYSTEM_PROMPT = "SYSTEM_PROMPT"
        private const val ACTIVE_AGENT_ID = "ACTIVE_AGENT_ID"
        private const val DEFAULT_CALENDAR = "DEFAULT_CALENDAR"
        private const val GIGA_MODEL = "GIGA_MODEL"
        private const val NEEDS_ONBOARDING = "NEEDS_ONBOARDING"
        private const val ONBOARDING_COMPLETED = "ONBOARDING_COMPLETED"
        private const val REQUEST_TIMEOUT_MILLIS = "REQUEST_TIMEOUT_MILLIS"
        private const val CONTEXT_SIZE = "CONTEXT_SIZE"
        private const val INITIAL_WINDOW_WIDTH_DP = "INITIAL_WINDOW_WIDTH_DP"
        private const val INITIAL_WINDOW_HEIGHT_DP = "INITIAL_WINDOW_HEIGHT_DP"
        private const val TEMPERATURE = "TEMPERATURE"
        private const val FORBIDDEN_FOLDERS = "FORBIDDEN_FOLDERS"
        private const val EMBEDDINGS_MODEL = "EMBEDDINGS_MODEL"
        private const val VOICE_RECOGNITION_MODEL = "VOICE_RECOGNITION_MODEL"
        private const val MCP_SERVERS_JSON = "MCP_SERVERS_JSON"
        private const val MCP_SERVERS_FILE = "MCP_SERVERS_FILE"
        const val REGION_RU = "ru"
        const val REGION_EN = "en"
        private val DEFAULT_FORBIDDEN_FOLDERS = listOf(
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

fun SettingsProvider.hasKey(provider: LlmProvider): Boolean = when (provider) {
    LlmProvider.GIGA -> !gigaChatKey.isNullOrBlank()
    LlmProvider.QWEN -> !qwenChatKey.isNullOrBlank()
    LlmProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
    LlmProvider.ANTHROPIC -> !anthropicKey.isNullOrBlank()
    LlmProvider.OPENAI -> !openaiKey.isNullOrBlank()
    LlmProvider.LOCAL -> true
}

fun SettingsProvider.hasKey(provider: VoiceRecognitionProvider): Boolean = when (provider) {
    VoiceRecognitionProvider.SALUTE_SPEECH -> !saluteSpeechKey.isNullOrBlank()
    VoiceRecognitionProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
    VoiceRecognitionProvider.OPENAI -> !openaiKey.isNullOrBlank()
}

fun main() {
    val s: SettingsProviderImpl = SettingsProviderImpl(ConfigStore)
    s.needsOnboarding = false
    s.onboardingCompleted = false
}

private fun defaultLocalProviderAvailability(): LocalProviderAvailability {
    val hostInfoProvider = LocalHostInfoProvider()
    return LocalProviderAvailability(
        hostInfoProvider = hostInfoProvider,
        modelStore = LocalModelStore(),
        bridgeLoader = LocalBridgeLoader(hostInfoProvider),
    )
}
