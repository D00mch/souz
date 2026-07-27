package ru.souz.backend.agent.runtime

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

/** Immutable compiled-tool policy captured for one backend execution request. */
internal class BackendRequestToolsFilter(
    enabledToolNames: Set<String>?,
) : AgentToolsFilter {
    private val enabledToolNames: Set<String>? = enabledToolNames?.toSet()

    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> =
        enabledToolNames?.let { enabled ->
            toolsByCategory.mapValues { (_, tools) ->
                tools.filterKeys { toolName -> toolName in enabled }
            }
        } ?: toolsByCategory
}

/** Request-scoped execution catalog with the compiled-tool policy applied eagerly. */
internal class BackendRequestToolCatalog(
    delegate: AgentToolCatalog,
    toolsFilter: AgentToolsFilter,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        toolsFilter.applyFilter(delegate.toolsByCategory)
            .mapValues { (_, tools) -> tools.toMap() }
            .toMap()
}
