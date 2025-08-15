package com.dumch.tool.config

import java.util.prefs.Preferences
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

object ConfigStore {
    @PublishedApi
    internal val prefs: Preferences = Preferences.userNodeForPackage(ConfigStore::class.java)
    @PublishedApi
    internal val mapper = jacksonObjectMapper()

    fun put(key: String, value: Any) {
        val str = when (value) {
            is String -> value
            is Int, is Long, is Float, is Double, is Boolean -> value.toString()
            else -> mapper.writeValueAsString(value)
        }
        prefs.put(key, str)
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
                else -> mapper.readValue<T>(str)
            } as T
        }.getOrNull()
    }
}
