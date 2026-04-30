package ru.souz.skill

import ru.souz.agent.skill.requiresRunBashCommand
import ru.souz.agent.spi.AgentToolFilterContext
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

class SkillAwareToolsFilter(
    private val delegate: AgentToolsFilter,
) : AgentToolsFilter {
    override fun applyFilter(
        toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
        context: AgentToolFilterContext,
    ): Map<ToolCategory, Map<String, LLMToolSetup>> {
        val base = delegate.applyFilter(toolsByCategory, context)
        val bashAllowed = context.activeSkill?.skill?.summary?.requiresRunBashCommand() == true
        if (bashAllowed) return base

        return base.mapValues { (_, tools) ->
            tools.filterKeys { toolName -> toolName != "RunBashCommand" }
        }
    }
}
