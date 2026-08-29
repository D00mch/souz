package ru.souz.backend.common

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.tool.LLM_BACKED_TOOL_NAMES
import ru.souz.tool.ToolCategory
import ru.souz.tool.composeToolCatalogs
import ru.souz.tool.immutableToolCatalogSnapshot
import ru.souz.tool.web.ToolWebImageSearch

/**
 * The backend's only answer to which tools it may host, advertise, and execute.
 *
 * Bootstrap capabilities, `enabledTools` validation, and request-scoped execution selection all
 * read this object, so adding or moving a backend tool is one category or name change here.
 */
object BackendToolCapabilityPolicy {
    /** Process/runtime categories the backend can host. Unlisted categories stay desktop-only. */
    val safeCategories: Set<ToolCategory> = setOf(
        ToolCategory.FILES,
        ToolCategory.IMAGE,
        ToolCategory.IMAGE_GENERATION,
        ToolCategory.WEB_SEARCH,
        ToolCategory.DATA_ANALYTICS,
        ToolCategory.CALCULATOR,
        ToolCategory.CHANNEL_MESSAGING,
        ToolCategory.OAUTH,
    )

    /** Advertised tools whose LLM dependency is bound per execution, so no process catalog holds them. */
    val executionBoundToolNames: Set<String> = LLM_BACKED_TOOL_NAMES

    /** Tools that sit in a safe category yet are neither advertised nor executable on the backend. */
    val deniedToolNames: Set<String> = setOf(ToolWebImageSearch.NAME)

    /** The hostable view of a process catalog: safe categories only, denied names removed. */
    fun hostableTools(processToolCatalog: AgentToolCatalog): AgentToolCatalog =
        immutableToolCatalogSnapshot(
            ToolCategory.entries.associateWith { category ->
                if (category in safeCategories) {
                    processToolCatalog.toolsByCategory[category].orEmpty()
                        .filterKeys { toolName -> toolName !in deniedToolNames }
                } else {
                    emptyMap()
                }
            }
        )

    /** Names the backend advertises and accepts in a user's `enabledTools`, in stable order. */
    fun advertisedToolNames(processToolCatalog: AgentToolCatalog): Set<String> =
        buildSet {
            hostableTools(processToolCatalog).toolsByCategory.values.forEach { tools -> addAll(tools.keys) }
            addAll(executionBoundToolNames)
        }.toSortedSet()

    /**
     * Compiled tools one execution may call: the hostable process tools plus the execution-bound
     * LLM tools, narrowed to [enabledToolNames] when the user pinned a selection.
     */
    fun selectExecutionTools(
        processToolCatalog: AgentToolCatalog,
        executionLlmToolCatalog: AgentToolCatalog,
        enabledToolNames: Set<String>?,
    ): AgentToolCatalog {
        val hostableTools = composeToolCatalogs(
            listOf(hostableTools(processToolCatalog), executionLlmToolCatalog)
        )
        if (enabledToolNames == null) {
            return hostableTools
        }
        return immutableToolCatalogSnapshot(
            hostableTools.toolsByCategory.mapValues { (_, tools) ->
                tools.filterKeys { toolName -> toolName in enabledToolNames }
            }
        )
    }
}

/** Names exposed by the backend, including tools whose LLM dependency is bound per execution. */
data class BackendAvailableToolNames(
    val values: Set<String>,
) {
    companion object {
        fun fromProcessCatalog(toolCatalog: AgentToolCatalog): BackendAvailableToolNames =
            BackendAvailableToolNames(BackendToolCapabilityPolicy.advertisedToolNames(toolCatalog))
    }
}
