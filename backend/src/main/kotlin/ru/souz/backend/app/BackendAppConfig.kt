package ru.souz.backend.app

import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.config.SystemBackendConfigSource
import ru.souz.backend.storage.StorageMode

data class BackendAppConfig(
    val featureFlags: BackendFeatureFlags,
    val storageMode: StorageMode,
    val proxyToken: String?,
) {
    fun validate(): BackendAppConfig {
        storageMode.requireSupported()
        return this
    }

    companion object {
        fun load(source: BackendConfigSource = SystemBackendConfigSource): BackendAppConfig =
            BackendAppConfig(
                featureFlags = BackendFeatureFlags.load(source),
                storageMode = StorageMode.load(source),
                proxyToken = source.value(
                    envKey = "SOUZ_BACKEND_PROXY_TOKEN",
                    propertyKey = "souz.backend.proxyToken",
                )?.trim()?.takeIf { it.isNotEmpty() },
            )
    }
}
