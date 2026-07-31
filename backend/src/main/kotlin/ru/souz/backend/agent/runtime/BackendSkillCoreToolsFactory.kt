package ru.souz.backend.agent.runtime

import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.skills.ToolRunSkillCommand

/** Creates request-scoped skill tools for the backend agent graphs. */
class BackendSkillCoreToolsFactory(
    private val skillRegistryRepository: SkillRegistryRepository,
    private val legacyCommandTool: LLMToolSetup,
    private val commandTool: ToolRunSkillCommand,
) {
    fun createGetSkillByName(
        toolCatalog: AgentToolCatalog,
        toolsFilter: AgentToolsFilter,
        approvalGate: SkillApprovalGate,
    ): ToolGetSkillByName =
        ToolGetSkillByName(
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
            repository = skillRegistryRepository,
            legacyCommandTool = legacyCommandTool,
            approvalGate = approvalGate,
        )

    fun createGetSkillsNamesByCategory(
        toolCatalog: AgentToolCatalog,
        toolsFilter: AgentToolsFilter,
    ): ToolGetSkillsNamesByCategory =
        ToolGetSkillsNamesByCategory(
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
        )

    fun createRuntimeCommand(
        toolCatalog: AgentToolCatalog,
        toolsFilter: AgentToolsFilter,
        approvalGate: SkillApprovalGate,
    ): ToolInvokeSkill =
        ToolInvokeSkill(
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
            repository = skillRegistryRepository,
            commandTool = commandTool,
            approvalGate = approvalGate,
        )
}
