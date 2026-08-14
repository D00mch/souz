package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory
import ru.souz.tool.composeToolCatalogs
import ru.souz.tool.immutableToolCatalogSnapshot
import ru.souz.tool.withoutFewShotExamples

/** Immutable tools available to one backend execution. Compiled-tool selection precedes client-tool merging. */
internal class BackendExecutionToolCatalog(
    compiledToolCatalog: AgentToolCatalog,
    executionLlmToolCatalog: AgentToolCatalog,
    enabledCompiledToolNames: Set<String>?,
    clientToolCatalog: AgentToolCatalog,
    includeFewShotExamples: Boolean,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>

    init {
        val enabledToolNames = enabledCompiledToolNames?.toSet()
        val compiledTools = composeToolCatalogs(
            listOf(compiledToolCatalog, executionLlmToolCatalog)
        )
        val selectedCompiledTools = immutableToolCatalogSnapshot(
            ToolCategory.entries.associateWith { category ->
                compiledTools.toolsByCategory.getValue(category).filterKeys { toolName ->
                    enabledToolNames == null || toolName in enabledToolNames
                }
            }
        )

        // Client tools intentionally win name collisions because the live client owns their execution boundary.
        val mergedTools = composeToolCatalogs(
            catalogs = listOf(selectedCompiledTools, clientToolCatalog),
            allowLaterSourceOverrides = true,
        )
        val executionSnapshot = if (includeFewShotExamples) {
            mergedTools
        } else {
            immutableToolCatalogSnapshot(
                ToolCategory.entries.associateWith { category ->
                    mergedTools.toolsByCategory.getValue(category)
                        .mapValues { (_, tool) -> tool.withoutFewShotExamples() }
                }
            )
        }
        toolsByCategory = executionSnapshot.toolsByCategory
    }
}
