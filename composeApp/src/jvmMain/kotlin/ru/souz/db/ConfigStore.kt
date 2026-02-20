package ru.souz.db

import com.fasterxml.jackson.module.kotlin.readValue
import ru.souz.giga.gigaJsonMapper
import java.util.prefs.Preferences

object ConfigStore {
    const val TG_BOT_TOKEN = "TG_BOT_TOKEN"
    const val TG_BOT_OWNER_ID = "TG_BOT_OWNER_ID"
    const val TG_BOT_USERNAME = "TG_BOT_USERNAME"

    const val TG_BOT_TASK_TYPE = "TG_BOT_TASK_TYPE"
    const val TG_BOT_TASK_STEP = "TG_BOT_TASK_STEP"
    const val TG_BOT_TASK_START_MSG_ID = "TG_BOT_TASK_START_MSG_ID"

    @PublishedApi // to use prefs inside the reified (inlined) `get`
    internal val prefs: Preferences = Preferences.userNodeForPackage(ConfigStore::class.java)

    fun put(key: String, value: Any) {
        val str = when (value) {
            is String -> value
            is Int, is Long, is Float, is Double, is Boolean -> value.toString()
            else -> gigaJsonMapper.writeValueAsString(value)
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
                else -> gigaJsonMapper.readValue<T>(str)
            } as T
        }.getOrNull()
    }
}

fun main() {
    ConfigStore.put("abc", 1)
    val result: Int? = ConfigStore.get<Int>("abc")
    println("result $result")
}
