package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

internal class BackendMergedToolCatalog(
    vararg catalogs: AgentToolCatalog,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        catalogs.flatMap { it.toolsByCategory.keys }.toSet().associateWith { category ->
            catalogs.fold(emptyMap()) { tools, catalog ->
                tools + catalog.toolsByCategory[category].orEmpty()
            }
        }
}
