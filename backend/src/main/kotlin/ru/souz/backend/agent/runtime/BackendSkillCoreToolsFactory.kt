package ru.souz.backend.agent.runtime

import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.skills.ToolGetSkills
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.skills.ToolRunSkillCommand

data class BackendSkillCoreTools(
    val getSkillsTool: LLMToolSetup,
    val getKnowledgeTool: LLMToolSetup,
    val runtimeCommandTool: LLMToolSetup,
)

/** Creates the skills-oriented graph's filtered tools for one backend request. */
class BackendSkillCoreToolsFactory(
    private val skillRegistryRepository: SkillRegistryRepository,
    private val legacyCommandTool: LLMToolSetup,
    private val getKnowledgeTool: LLMToolSetup,
    private val commandTool: ToolRunSkillCommand,
) {
    fun create(
        toolCatalog: AgentToolCatalog,
        toolsFilter: AgentToolsFilter,
    ): BackendSkillCoreTools = BackendSkillCoreTools(
        getSkillsTool = ToolGetSkills(
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
            repository = skillRegistryRepository,
            legacyCommandTool = legacyCommandTool,
        ),
        getKnowledgeTool = getKnowledgeTool,
        runtimeCommandTool = ToolInvokeSkill(
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
            repository = skillRegistryRepository,
            commandTool = commandTool,
        ),
    )
}
