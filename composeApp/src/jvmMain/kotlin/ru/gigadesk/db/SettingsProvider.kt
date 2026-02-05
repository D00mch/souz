package ru.gigadesk.db

import ru.gigadesk.agent.DEFAULT_SYSTEM_PROMPT
import ru.gigadesk.giga.GigaModel
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

interface SettingsProvider {
    fun getSystemPromptForModel(model: GigaModel): String?
    fun setSystemPromptForModel(model: GigaModel, prompt: String?)

    var gigaChatKey: String?
    var saluteSpeechKey: String?
    var supportEmail: String?
    var systemPrompt: String?
    var defaultCalendar: String?
    var gigaModel: GigaModel
    var useFewShotExamples: Boolean
    var useGrpc: Boolean
    var needsOnboarding: Boolean
    var requestTimeoutMillis: Long
    var initialWindowWidthDp: Int
    var initialWindowHeightDp: Int
    var temperature: Float
    var forbiddenFolders: List<String>
}

class SettingsProviderImpl(private val configStore: ConfigStore) : SettingsProvider {

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

    private var _fewShotsDelegate: String? by keyDelegate(configKey = USE_FEW_SHOTS, envKey = USE_FEW_SHOTS)
    private var _gigaModelDelegate: String? by keyDelegate(configKey = GIGA_MODEL, envKey = GIGA_MODEL)
    private var _useGrpcDelegate: String? by keyDelegate(configKey = USE_GRPC, envKey = USE_GRPC)
    private var _requestTimeoutDelegate: String? by keyDelegate(
        configKey = REQUEST_TIMEOUT_MILLIS,
        envKey = REQUEST_TIMEOUT_MILLIS
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

    override var gigaChatKey: String? by keyDelegate(configKey = GIGA_CHAT_KEY, envKey = "GIGA_KEY")
    override var saluteSpeechKey: String? by keyDelegate(configKey = SALUTE_SPEECH_KEY, envKey = "VOICE_KEY")
    override var supportEmail: String? by keyDelegate(configKey = SUPPORT_EMAIL, envKey = SUPPORT_EMAIL)
    override var systemPrompt: String? by keyDelegate(configKey = SYSTEM_PROMPT, envKey = SYSTEM_PROMPT)
    override var defaultCalendar: String? by keyDelegate(configKey = DEFAULT_CALENDAR, envKey = DEFAULT_CALENDAR)
    override var gigaModel: GigaModel
        get() = _gigaModelDelegate?.let { value ->
            GigaModel.entries.firstOrNull { model ->
                model.name.equals(value, ignoreCase = true) || model.alias.equals(value, ignoreCase = true)
            }
        } ?: GigaModel.Max
        set(value) {
            _gigaModelDelegate = value.alias
        }

    override var useFewShotExamples: Boolean
        get() = _fewShotsDelegate?.lowercase() == "true"
        set(value) {
            _fewShotsDelegate = value.toString()
        }

    override var useGrpc: Boolean
        get() = _useGrpcDelegate?.lowercase() == "true"
        set(value) {
            _useGrpcDelegate = value.toString()
        }

    override var needsOnboarding: Boolean
        get() = _needsOnboardingDelegate?.toBooleanStrictOrNull() ?: false
        set(value) {
            _needsOnboardingDelegate = value.toString()
        }

    override var requestTimeoutMillis: Long
        get() = _requestTimeoutDelegate?.toLongOrNull() ?: 10_000L
        set(value) {
            _requestTimeoutDelegate = value.toString()
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
        private const val SALUTE_SPEECH_KEY = "SALUTE_SPEECH_KEY"
        private const val USE_FEW_SHOTS = "USE_FEW_SHOTS"
        private const val USE_GRPC = "USE_GRPC"
        private const val SUPPORT_EMAIL = "SUPPORT_EMAIL"
        private const val SYSTEM_PROMPT = "SYSTEM_PROMPT"
        private const val DEFAULT_CALENDAR = "DEFAULT_CALENDAR"
        private const val GIGA_MODEL = "GIGA_MODEL"
        private const val NEEDS_ONBOARDING = "NEEDS_ONBOARDING"
        private const val REQUEST_TIMEOUT_MILLIS = "REQUEST_TIMEOUT_MILLIS"
        private const val INITIAL_WINDOW_WIDTH_DP = "INITIAL_WINDOW_WIDTH_DP"
        private const val INITIAL_WINDOW_HEIGHT_DP = "INITIAL_WINDOW_HEIGHT_DP"
        private const val TEMPERATURE = "TEMPERATURE"
        private const val FORBIDDEN_FOLDERS = "FORBIDDEN_FOLDERS"
        private val DEFAULT_FORBIDDEN_FOLDERS = listOf("~/Library/")
    }
}
