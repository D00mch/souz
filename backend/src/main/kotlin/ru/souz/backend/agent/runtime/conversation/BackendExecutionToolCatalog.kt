package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.BackendToolCapabilityPolicy
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
): AgentToolCatalog {
    val selectedCompiledTools = BackendToolCapabilityPolicy.selectExecutionTools(
        processToolCatalog = compiledToolCatalog,
        executionLlmToolCatalog = executionLlmToolCatalog,
        enabledToolNames = enabledCompiledToolNames,
    )

    // Client tools intentionally win name collisions because the live client owns their execution boundary.
    val mergedTools = composeToolCatalogs(
        catalogs = listOf(selectedCompiledTools, clientToolCatalog),
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
