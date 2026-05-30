package ru.souz.backend.config

interface BackendConfigSource {
    fun env(key: String): String?
    fun property(key: String): String?

    fun value(envKey: String, propertyKey: String): String? =
        env(envKey) ?: property(propertyKey)
}

object SystemBackendConfigSource : BackendConfigSource {
    override fun env(key: String): String? = System.getenv(key)

    override fun property(key: String): String? = System.getProperty(key)
}
