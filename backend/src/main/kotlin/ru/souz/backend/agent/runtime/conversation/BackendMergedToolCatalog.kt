package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

internal class BackendMergedToolCatalog(
    primary: AgentToolCatalog,
    additional: AgentToolCatalog,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        (primary.toolsByCategory.keys + additional.toolsByCategory.keys).associateWith { category ->
            primary.toolsByCategory[category].orEmpty() + additional.toolsByCategory[category].orEmpty()
        }
}
