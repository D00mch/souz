package ru.souz.tool.skills

import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.immutableToolCatalogSnapshot

/** One lookup or spawn selection uses one catalog snapshot and the caller's scoped bundle loader. */
internal class SkillResolver(
    toolCatalog: AgentToolCatalog,
    toolsFilter: AgentToolsFilter,
    private val loadBundle: suspend (userId: String, skillId: SkillId) -> SkillBundle?,
    private val approvalGate: SkillApprovalGate?,
) {
    private val catalog = immutableToolCatalogSnapshot(toolCatalog.toolsByCategory)
    val enabledTools = AgentTools(toolsFilter.applyFilter(catalog.toolsByCategory))

    suspend fun resolve(skillId: SkillId, userId: String): SkillResolution {
        enabledTools.byName[skillId.value]?.let { return SkillResolution.Compiled(it) }
        val bundle = loadBundle(userId, skillId)
            ?: return if (catalog.toolsByCategory.values.any { skillId.value in it }) {
                SkillResolution.Error("skill_disabled", "Tool-backed Skill is disabled: ${skillId.value}")
            } else {
                SkillResolution.Error("skill_not_found", "Skill is unavailable: ${skillId.value}")
            }
        return when (val approval = approvalGate?.ensureApproved(SkillApprovalGate.Input(userId, skillId, bundle))) {
            is SkillApprovalGate.Result.Approved -> SkillResolution.Bundle(approval.bundle, approval.bundleHash)
            is SkillApprovalGate.Result.Rejected -> SkillResolution.Error("skill_validation_rejected", approval.reason)
            null -> SkillResolution.Bundle(bundle, SkillBundleHasher.hash(bundle))
        }
    }
}

internal sealed interface SkillResolution {
    data class Compiled(val tool: LLMToolSetup) : SkillResolution
    data class Bundle(val bundle: SkillBundle, val bundleHash: String) : SkillResolution
    data class Error(val code: String, val message: String) : SkillResolution
}
