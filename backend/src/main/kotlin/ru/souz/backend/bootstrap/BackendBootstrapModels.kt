package ru.souz.backend.bootstrap

import ru.souz.backend.config.BackendFeatureFlags

data class BootstrapResponse(
    val user: BootstrapUser,
    val features: BackendFeatureFlags,
    val storage: BootstrapStorage,
    val capabilities: BootstrapCapabilities,
    val settings: BootstrapSettings,
)

data class BootstrapUser(
    val id: String,
)

data class BootstrapStorage(
    val mode: String,
)

data class BootstrapCapabilities(
    val models: List<BootstrapModelCapability>,
    val tools: List<BootstrapToolCapability>,
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

data class BootstrapSettings(
    val defaultModel: String,
    val contextSize: Int,
    val temperature: Float,
    val locale: String,
    val timeZone: String,
    val systemPrompt: String?,
    val enabledTools: List<String>,
    val showToolEvents: Boolean,
    val streamingMessages: Boolean,
)
