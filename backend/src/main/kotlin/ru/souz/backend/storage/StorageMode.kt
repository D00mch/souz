package ru.souz.backend.storage

import ru.souz.backend.common.BackendConfigurationException
import ru.souz.backend.config.BackendConfigSource
import ru.souz.backend.config.SystemBackendConfigSource

enum class StorageMode(val value: String) {
    MEMORY("memory"),
    FILESYSTEM("filesystem"),
    POSTGRES("postgres");

    fun requireSupported(): StorageMode =
        this

    companion object {
        fun load(source: BackendConfigSource = SystemBackendConfigSource): StorageMode {
            val rawValue = source.value(
                envKey = "SOUZ_STORAGE_MODE",
                propertyKey = "souz.backend.storageMode",
            )?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?: return MEMORY
            return entries.firstOrNull { it.value == rawValue }
                ?: throw BackendConfigurationException(
                    "Unsupported storage mode '$rawValue'. Supported values: memory, filesystem, postgres."
                )
        }
    }
}
