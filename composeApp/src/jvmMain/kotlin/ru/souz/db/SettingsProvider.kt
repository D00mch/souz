package ru.souz.db

import ru.souz.agent.DEFAULT_SYSTEM_PROMPT
import ru.souz.giga.EmbeddingsModel
import ru.souz.giga.EmbeddingsProvider
import ru.souz.giga.DEFAULT_MAX_TOKENS
import ru.souz.giga.GigaModel
import ru.souz.giga.LlmBuildProfile
import ru.souz.giga.LlmProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

interface SettingsProvider {
    fun getSystemPromptForModel(model: GigaModel): String?
    fun setSystemPromptForModel(model: GigaModel, prompt: String?)

    var gigaChatKey: String?
    var qwenChatKey: String?
    var aiTunnelKey: String?
    var anthropicKey: String?
    var openaiKey: String?
    var saluteSpeechKey: String?
    var supportEmail: String?
    var systemPrompt: String?
    var defaultCalendar: String?
    var gigaModel: GigaModel
    var useFewShotExamples: Boolean
    var useStreaming: Boolean
    var safeModeEnabled: Boolean
    var needsOnboarding: Boolean
    var onboardingCompleted: Boolean
    var requestTimeoutMillis: Long
    var contextSize: Int
    var initialWindowWidthDp: Int
    var initialWindowHeightDp: Int
    var temperature: Float
    var forbiddenFolders: List<String>
    var embeddingsModel: EmbeddingsModel
    var mcpServersJson: String?
    var mcpServersFile: String?
}

class SettingsProviderImpl(private val configStore: ConfigStore) : SettingsProvider {

    private var _fewShotsDelegate: String? by keyDelegate(configKey = USE_FEW_SHOTS, envKey = USE_FEW_SHOTS)
    private var _gigaModelDelegate: String? by keyDelegate(configKey = GIGA_MODEL, envKey = GIGA_MODEL)
    private var _useStreamingDelegate: String? by keyDelegate(configKey = USE_STREAMING, envKey = USE_STREAMING)
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

    init {
        // apply defaults
        if (_safeModeDelegate.isNullOrBlank()) _safeModeDelegate = "true"
    }

    override fun getSystemPromptForModel(model: GigaModel): String? {
        val key = "${SYSTEM_PROMPT}_${model.name}"
        return configStore.get<String>(key)
    }

    override fun setSystemPromptForModel(model: GigaModel, prompt: String?) {
        val key = "${SYSTEM_PROMPT}_${model.name}"
        when {
            prompt.isNullOrBlank() || prompt == DEFAULT_SYSTEM_PROMPT -> configStore.rm(key)
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
    override var systemPrompt: String? by keyDelegate(configKey = SYSTEM_PROMPT, envKey = SYSTEM_PROMPT)
    override var defaultCalendar: String? by keyDelegate(configKey = DEFAULT_CALENDAR, envKey = DEFAULT_CALENDAR)
    override var gigaModel: GigaModel
        get() = _gigaModelDelegate?.let { value ->
            GigaModel.entries.firstOrNull { model ->
                model.name.equals(value, ignoreCase = true) || model.alias.equals(value, ignoreCase = true)
            }
        }?.let(LlmBuildProfile::normalizeModel)
            ?: LlmBuildProfile.defaultModel
        set(value) {
            _gigaModelDelegate = LlmBuildProfile.normalizeModel(value).alias
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

    override var mcpServersJson: String? by keyDelegate(
        configKey = MCP_SERVERS_JSON,
        envKey = MCP_SERVERS_JSON
    )

    override var mcpServersFile: String? by keyDelegate(
        configKey = MCP_SERVERS_FILE,
        envKey = MCP_SERVERS_FILE
    )

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
        private const val GIGA_CHAT_KEY = "GIGA_CHAT_KEY"
        private const val QWEN_CHAT_KEY = "QWEN_CHAT_KEY"
        private const val AI_TUNNEL_KEY = "AI_TUNNEL_KEY"
        private const val ANTHROPIC_KEY = "ANTHROPIC_KEY"
        private const val OPENAI_KEY = "OPENAI_KEY"
        private const val SALUTE_SPEECH_KEY = "SALUTE_SPEECH_KEY"
        private const val USE_FEW_SHOTS = "USE_FEW_SHOTS"
        private const val USE_STREAMING = "USE_STREAMING"
        private const val SAFE_MODE_ENABLED = "SAFE_MODE_ENABLED"
        private const val USE_GRPC_LEGACY = "USE_GRPC"
        private const val SUPPORT_EMAIL = "SUPPORT_EMAIL"
        private const val SYSTEM_PROMPT = "SYSTEM_PROMPT"
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
        private const val MCP_SERVERS_JSON = "MCP_SERVERS_JSON"
        private const val MCP_SERVERS_FILE = "MCP_SERVERS_FILE"
        private val DEFAULT_FORBIDDEN_FOLDERS = listOf("~/Library/")
    }
}

fun SettingsProvider.hasKey(provider: LlmProvider): Boolean = when (provider) {
    LlmProvider.GIGA -> !gigaChatKey.isNullOrBlank()
    LlmProvider.QWEN -> !qwenChatKey.isNullOrBlank()
    LlmProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
    LlmProvider.ANTHROPIC -> !anthropicKey.isNullOrBlank()
    LlmProvider.OPENAI -> !openaiKey.isNullOrBlank()
}

fun SettingsProvider.hasKey(provider: EmbeddingsProvider): Boolean = when (provider) {
    EmbeddingsProvider.GIGA -> !gigaChatKey.isNullOrBlank()
    EmbeddingsProvider.QWEN -> !qwenChatKey.isNullOrBlank()
    EmbeddingsProvider.AI_TUNNEL -> !aiTunnelKey.isNullOrBlank()
    EmbeddingsProvider.ANTHROPIC -> !anthropicKey.isNullOrBlank()
    EmbeddingsProvider.OPENAI -> !openaiKey.isNullOrBlank()
}
