package ru.gigadesk.db

import ru.gigadesk.giga.GigaModel
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class SettingsProvider(private val configStore: ConfigStore) {
    private var _fewShotsDelegate: String? by keyDelegate(configKey = USE_FEW_SHOTS, envKey = USE_FEW_SHOTS)
    private var _gigaModelDelegate: String? by keyDelegate(configKey = GIGA_MODEL, envKey = GIGA_MODEL)

    var gigaChatKey: String? by keyDelegate(configKey = GIGA_CHAT_KEY, envKey = "GIGA_KEY")
    var saluteSpeechKey: String? by keyDelegate(configKey = SALUTE_SPEECH_KEY, envKey = "VOICE_KEY")
    var isSetupCompleted: Boolean
        get() = _isSetupCompletedDelegate?.lowercase() == "true"
        set(value) { _isSetupCompletedDelegate = value.toString() }
    var supportEmail: String? by keyDelegate(configKey = SUPPORT_EMAIL, envKey = SUPPORT_EMAIL)
    var systemPrompt: String? by keyDelegate(configKey = SYSTEM_PROMPT, envKey = SYSTEM_PROMPT)
    var defaultCalendar: String? by keyDelegate(configKey = DEFAULT_CALENDAR, envKey = DEFAULT_CALENDAR)
    var gigaModel: GigaModel
        get() = _gigaModelDelegate?.let { value ->
            GigaModel.entries.firstOrNull { model ->
                model.name.equals(value, ignoreCase = true) || model.alias.equals(value, ignoreCase = true)
            }
        } ?: GigaModel.Max
        set(value) { _gigaModelDelegate = value.alias }

    var useFewShotExamples: Boolean
        get() = _fewShotsDelegate?.lowercase() == "true"
        set(value) { _fewShotsDelegate = value.toString() }

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
        private const val SETUP_COMPLETED = "SETUP_COMPLETED"
        private const val USE_FEW_SHOTS = "USE_FEW_SHOTS"
        private const val SUPPORT_EMAIL = "SUPPORT_EMAIL"
        private const val SYSTEM_PROMPT = "SYSTEM_PROMPT"
        private const val DEFAULT_CALENDAR = "DEFAULT_CALENDAR"
        private const val GIGA_MODEL = "GIGA_MODEL"
    }

    private var _isSetupCompletedDelegate: String? by keyDelegate(
        configKey = SETUP_COMPLETED,
        envKey = SETUP_COMPLETED
    )
}
