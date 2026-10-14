package ru.souz.backend.agent.runtime.conversation

import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.tool.composeToolCatalogs
import ru.souz.tool.immutableToolCatalogSnapshot
import ru.souz.tool.withoutFewShotExamples

/** Immutable tools available to one backend execution. Compiled-tool selection precedes client-tool merging. */
internal fun backendExecutionToolCatalog(
    compiledToolCatalog: AgentToolCatalog,
    executionLlmToolCatalog: AgentToolCatalog,
    enabledCompiledToolNames: Set<String>?,
    clientToolCatalog: AgentToolCatalog,
    includeFewShotExamples: Boolean,
    metrics: BackendMetrics? = null,
    pluginToolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
): AgentToolCatalog {
    val selectedCompiledTools = BackendToolCapabilityPolicy.selectExecutionTools(
        processToolCatalog = compiledToolCatalog,
        executionLlmToolCatalog = executionLlmToolCatalog,
        enabledToolNames = enabledCompiledToolNames,
    )

    val selectedTools = composeToolCatalogs(selectedCompiledTools, pluginToolCatalog)
    require(pluginToolCatalog.toolsByCategory.values.flatMap { it.keys }.none { name ->
        clientToolCatalog.toolsByCategory.values.any { name in it }
    }) { "Plugin/client tool collision" }

    // Client tools intentionally win name collisions because the live client owns their execution boundary.
    val mergedTools = composeToolCatalogs(
        if (metrics == null) selectedTools else immutableToolCatalogSnapshot(
            selectedTools.toolsByCategory.mapValues { (category, tools) ->
                tools.mapValues { (_, tool) -> metrics.instrumentTool(tool, category) }
            }
        ),
        clientToolCatalog,
        allowLaterSourceOverrides = true,
    )
    if (includeFewShotExamples) {
        return mergedTools
    }
    return immutableToolCatalogSnapshot(
        mergedTools.toolsByCategory.mapValues { (_, tools) ->
            tools.mapValues { (_, tool) -> tool.withoutFewShotExamples() }
        }
    )
}
