package ru.abledo.db

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class SettingsProvider(private val configStore: ConfigStore) {
    private var _fewShotsDelegate: String? by keyDelegate(configKey = USE_FEW_SHOTS, envKey = USE_FEW_SHOTS)

    var gigaChatKey: String? by keyDelegate(configKey = GIGA_CHAT_KEY, envKey = "GIGA_KEY")
    var saluteSpeechKey: String? by keyDelegate(configKey = SALUTE_SPEECH_KEY, envKey = "VOICE_KEY")
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
        private const val USE_FEW_SHOTS = "USE_FEW_SHOTS"
    }
}