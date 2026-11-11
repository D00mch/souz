package ru.souz.tool.skills

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMToolSetup

class ToolGetSkillsByCategory(
    catalogProvider: () -> AgentToolCatalog,
    toolsFilter: AgentToolsFilter,
) : LLMToolSetup by CategoryDiscovery(catalogProvider, toolsFilter, details = true) {
    companion object {
        const val NAME = "GetSkillsByCategory"
    }
}
