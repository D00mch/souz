package ru.souz.backend.plugins

import ru.souz.backend.config.BackendConfigSource

data class PluginRuntimeConfig(
    val pollMillis: Long = 1_000,
    val prepareMillis: Long = 30_000,
    val callMillis: Long = 60_000,
    val cleanupMillis: Long = 10_000,
    val shutdownMillis: Long = 30_000,
    val retryMillis: Long = 30_000,
    val concurrentPreparations: Int = 2,
    val concurrentCalls: Int = 32,
    val liveVersions: Int = 2,
) {
    init {
        require(pollMillis > 0 && prepareMillis > 0 && callMillis > 0 && cleanupMillis > 0 &&
            shutdownMillis > 0 && retryMillis > 0) { "Plugin deadlines must be positive." }
        require(concurrentPreparations in 1..16 && concurrentCalls in 1..1_024 && liveVersions in 1..8) {
            "Invalid plugin concurrency or live-version limit."
        }
    }

    companion object {
        fun load(source: BackendConfigSource): PluginRuntimeConfig {
            fun number(key: String, default: Long): Long = source.value("SOUZ_PLUGINS_$key", "souz.plugins.${key.lowercase()}")
                ?.let { requireNotNull(it.toLongOrNull()) { "Invalid plugin $key." } } ?: default
            return PluginRuntimeConfig(
                pollMillis = number("POLL_MILLIS", 1_000),
                callMillis = number("CALL_MILLIS", 60_000),
                prepareMillis = number("PREPARE_MILLIS", 30_000),
                cleanupMillis = number("CLEANUP_MILLIS", 10_000),
            )
        }
    }
}
