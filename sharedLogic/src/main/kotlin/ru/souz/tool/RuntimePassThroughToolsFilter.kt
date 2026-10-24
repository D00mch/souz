package ru.souz.tool

import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMToolSetup

object RuntimePassThroughToolsFilter : AgentToolsFilter {
    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
}
