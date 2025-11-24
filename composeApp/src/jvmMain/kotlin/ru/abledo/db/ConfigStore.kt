package ru.abledo.db

import com.fasterxml.jackson.module.kotlin.readValue
import ru.abledo.giga.objectMapper
import java.util.prefs.Preferences
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object ConfigStore {
    @PublishedApi // to use prefs inside the reified (inlined) `get`
    internal val prefs: Preferences = Preferences.userNodeForPackage(ConfigStore::class.java)

    fun put(key: String, value: Any) {
        val str = when (value) {
            is String -> value
            is Int, is Long, is Float, is Double, is Boolean -> value.toString()
            else -> objectMapper.writeValueAsString(value)
        }
        prefs.put(key, str)
    }

    @Suppress("unused")
    fun rm(key: String) {
        prefs.remove(key)
    }

    inline fun <reified T : Any> get(key: String, default: T): T =
        get<T>(key) ?: default

    inline fun <reified T : Any> get(key: String): T? {
        val str = prefs.get(key, null) ?: return null
        return runCatching {
            when (T::class) {
                Int::class -> str.toInt()
                Long::class -> str.toLong()
                Float::class -> str.toFloat()
                Double::class -> str.toDouble()
                Boolean::class -> str.toBooleanStrict()
                String::class -> str
                else -> objectMapper.readValue<T>(str)
            } as T
        }.getOrNull()
    }
}

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

fun main() {
    ConfigStore.put("abc", 1)
    val result: Int? = ConfigStore.get<Int>("abc")
    println("result $result")
}