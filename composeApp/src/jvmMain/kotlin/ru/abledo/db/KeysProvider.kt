package ru.abledo.db

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class KeysProvider(private val configStore: ConfigStore) {

    var gigaChatKey: String? by keyDelegate(configKey = GIGA_CHAT_KEY, envKey = "GIGA_KEY")
    var saluteSpeechKey: String? by keyDelegate(configKey = SALUTE_SPEECH_KEY, envKey = "VOICE_KEY")

    private fun keyDelegate(configKey: String, envKey: String, sysPropKey: String = envKey) =
        @OptIn(ExperimentalAtomicApi::class)
        object : ReadWriteProperty<Any?, String?> {
            var cached = AtomicReference<String?>(null)

            override fun getValue(thisRef: Any?, property: KProperty<*>): String? =
                cached.load()
                    ?: configStore.get(configKey)
                    ?: System.getenv(envKey)
                    ?: System.getProperty(sysPropKey)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
                when (value) {
                    null, "" -> {
                        cached.store(null)
                        configStore.rm(configKey)
                    }

                    else -> {
                        cached.store(value)
                        configStore.put(configKey, value)
                    }
                }
            }
        }

    companion object {
        private const val GIGA_CHAT_KEY = "GIGA_CHAT_KEY"
        private const val SALUTE_SPEECH_KEY = "SALUTE_SPEECH_KEY"
    }
}