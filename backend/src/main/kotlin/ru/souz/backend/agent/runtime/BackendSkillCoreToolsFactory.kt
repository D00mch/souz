package ru.souz.backend.agent.runtime

import ru.souz.agent.AgentCoreTools
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsByCategory
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.skills.ToolRunSkillCommand

/** Creates request-scoped core tools for both backend agent graphs. */
class BackendSkillCoreToolsFactory(
    private val skillRegistryRepository: SkillRegistryRepository,
    private val legacyCommandTool: LLMToolSetup,
    private val getKnowledgeTool: LLMToolSetup,
    private val searchKnowledgeTool: LLMToolSetup,
    private val searchMemoryTool: LLMToolSetup,
    private val commandTool: ToolRunSkillCommand,
) {
    fun create(
        toolCatalog: AgentToolCatalog,
        toolsFilter: AgentToolsFilter,
        approvalGate: SkillApprovalGate,
    ): AgentCoreTools {
        val getSkillByName = ToolGetSkillByName(
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
            repository = skillRegistryRepository,
            legacyCommandTool = legacyCommandTool,
            approvalGate = approvalGate,
        )
        val getSkillsNamesByCategory = ToolGetSkillsNamesByCategory(
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
        )
        return AgentCoreTools(
            getSkillByNameTool = getSkillByName,
            getSkillsByCategoryTool = ToolGetSkillsByCategory(
                getSkillByName = getSkillByName,
                getSkillsNamesByCategory = getSkillsNamesByCategory,
            ),
            getSkillsNamesByCategoryTool = getSkillsNamesByCategory,
            getKnowledgeTool = getKnowledgeTool,
            searchKnowledgeTool = searchKnowledgeTool,
            searchMemoryTool = searchMemoryTool,
            runtimeCommandTool = ToolInvokeSkill(
                toolCatalog = toolCatalog,
                toolsFilter = toolsFilter,
                repository = skillRegistryRepository,
                commandTool = commandTool,
                approvalGate = approvalGate,
            ),
        )
    }
}
