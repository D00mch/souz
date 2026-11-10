package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.tool.withoutFewShotExamples

/** Immutable tools available to one backend execution. Compiled-tool selection precedes client-tool merging. */
internal fun backendExecutionToolCatalog(
    compiledToolCatalog: AgentToolCatalog,
    executionLlmToolCatalog: AgentToolCatalog,
    enabledCompiledToolNames: Set<String>?,
    clientToolCatalog: AgentToolCatalog,
    includeFewShotExamples: Boolean,
    metrics: BackendMetrics? = null,
    pluginToolCatalog: AgentToolCatalog = AgentToolCatalog(),
): AgentToolCatalog {
    val selectedCompiledTools = BackendToolCapabilityPolicy.selectExecutionTools(
        processToolCatalog = compiledToolCatalog,
        executionLlmToolCatalog = executionLlmToolCatalog,
        enabledToolNames = enabledCompiledToolNames,
        pluginToolCatalog = pluginToolCatalog,
    )

    val mergedTools = AgentToolCatalog.compose(
        if (metrics == null) selectedCompiledTools else selectedCompiledTools.mapTools { category, tool ->
            metrics.instrumentTool(tool, category)
        },
        clientToolCatalog,
    )
    if (includeFewShotExamples) {
        return mergedTools
    }
    return mergedTools.mapTools { _, tool -> tool.withoutFewShotExamples() }
}

/** Cache only the latest pair; old execution snapshots can be collected after discovery refreshes. */
internal class ComposedToolCatalog {
    private data class Snapshot(val base: AgentToolCatalog, val remote: AgentToolCatalog, val catalog: AgentToolCatalog)
    @Volatile private var snapshot: Snapshot? = null

    fun get(base: AgentToolCatalog, remote: AgentToolCatalog): AgentToolCatalog {
        val current = snapshot
        if (current?.base === base && current.remote === remote) return current.catalog
        val replacement = Snapshot(base, remote, AgentToolCatalog.compose(base, remote))
        snapshot = replacement
        return replacement.catalog
    }

    fun clear() {
        snapshot = null
    }
}
