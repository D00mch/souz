package ru.souz.backend.agent.runtime

import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga
import ru.souz.skilloauth.SkillOAuthApi
import ru.souz.tool.skills.ToolCheckOAuthStatus
import ru.souz.tool.skills.ToolConnectOAuthProvider
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import ru.souz.tool.skills.ToolRunSkillCommand
import ru.souz.tool.skills.ToolSafeApiCall

/** Creates request-scoped skill tools for the backend agent graphs. */
class BackendSkillCoreToolsFactory(
    private val skillBundleProvider: SkillBundleProvider,
    private val legacyCommandTool: LLMToolSetup,
    private val commandTool: ToolRunSkillCommand,
    private val skillOAuthApi: SkillOAuthApi? = null,
) {
    fun createGetSkillByName(
        toolCatalog: AgentToolCatalog,
        toolsFilter: AgentToolsFilter,
        approvalGate: SkillApprovalGate,
    ): ToolGetSkillByName =
        ToolGetSkillByName(
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
            skillBundleProvider = skillBundleProvider,
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
            skillBundleProvider = skillBundleProvider,
            commandTool = commandTool,
            approvalGate = approvalGate,
        )

    /**
     * Request-scoped `ToolCategory.OAUTH` tools, built with a real [approvalGate] — unlike the
     * DI-singleton instances the shared `runtimeToolsDiModule` binds (whose `approvalGate` always
     * resolves to null, since [SkillApprovalGate] is never bound in Kodein), so a
     * stored-but-unapproved skill bundle declaring `oauthProvider` could otherwise still drive a
     * real OAuth connection or authorized API call. Mirrors [createGetSkillByName]/
     * [createRuntimeCommand]'s existing request-scoped pattern. Returns an empty map — omitting the
     * category entirely, not just leaving it unusable — when no [SkillOAuthApi] is configured for
     * this deployment.
     */
    fun createOAuthTools(approvalGate: SkillApprovalGate): Map<String, LLMToolSetup> {
        val api = skillOAuthApi ?: return emptyMap()
        return listOf(
            ToolConnectOAuthProvider(
                skillBundleProvider = skillBundleProvider,
                skillOAuthApi = api,
                approvalGate = approvalGate,
            ).toGiga(),
            ToolCheckOAuthStatus(
                skillBundleProvider = skillBundleProvider,
                skillOAuthApi = api,
                approvalGate = approvalGate,
            ).toGiga(),
            ToolSafeApiCall(
                skillBundleProvider = skillBundleProvider,
                skillOAuthApi = api,
                approvalGate = approvalGate,
            ).toGiga(),
        ).associateBy { it.fn.name }
    }
}
