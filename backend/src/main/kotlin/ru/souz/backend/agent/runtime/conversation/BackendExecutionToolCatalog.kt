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
    )

    val serverTools = AgentToolCatalog.compose(selectedCompiledTools, pluginToolCatalog)
    val mergedTools = AgentToolCatalog.compose(
        if (metrics == null) serverTools else serverTools.mapTools { category, tool ->
            metrics.instrumentTool(tool, category)
        },
        clientToolCatalog,
    )
    if (includeFewShotExamples) {
        return mergedTools
    }
    return mergedTools.mapTools { _, tool -> tool.withoutFewShotExamples() }
}
