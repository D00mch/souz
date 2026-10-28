package ru.souz.backend.bootstrap

import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendV1SettingsDto

data class BootstrapResponse(
    val user: BootstrapUser,
    val features: BackendFeatureFlags,
    val capabilities: BootstrapCapabilities,
    val settings: BackendV1SettingsDto,
)

data class BootstrapUser(
    val id: String,
)

data class BootstrapCapabilities(
    val models: List<BootstrapModelCapability>,
    val tools: List<BootstrapToolCapability>,
    val plugins: List<BootstrapPluginCapability> = emptyList(),
)

data class BootstrapModelCapability(
    val provider: String,
    val model: String,
    val serverManagedKey: Boolean,
    val userManagedKey: Boolean,
)

data class BootstrapToolCapability(
    val name: String,
    val enabled: Boolean,
)

data class BootstrapPluginCapability(val id: String, val version: String, val tools: List<String>, val enabled: Boolean)
