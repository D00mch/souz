package ru.souz.backend.plugins

import ru.souz.agent.spi.AgentToolCatalog

internal data class PluginVersionStatus(
    val binding: PluginBinding,
    val phase: String,
    val executions: Int,
    val calls: Int,
    val error: String?,
)
internal data class PluginStatus(
    val id: String,
    val observedRevision: Long,
    val appliedDigest: String?,
    val versions: List<PluginVersionStatus>,
)
internal data class PluginSnapshot(
    val catalog: AgentToolCatalog = AgentToolCatalog(),
    val defaultBindings: List<PluginBinding> = emptyList(),
    val status: List<PluginStatus> = emptyList(),
    val registryError: String? = null,
)
