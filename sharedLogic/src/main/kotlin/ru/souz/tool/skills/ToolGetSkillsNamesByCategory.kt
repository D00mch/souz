package ru.souz.tool.skills

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMToolSetup

class ToolGetSkillsNamesByCategory(
    catalogProvider: () -> AgentToolCatalog,
    toolsFilter: AgentToolsFilter,
) : LLMToolSetup by CategoryDiscovery(catalogProvider, toolsFilter, details = false) {
    companion object {
        const val NAME = "GetSkillsNamesByCategory"
    }
}
