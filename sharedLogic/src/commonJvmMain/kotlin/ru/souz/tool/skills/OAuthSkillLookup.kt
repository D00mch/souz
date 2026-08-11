package ru.souz.tool.skills

import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.validation.SkillApprovalGate
import ru.souz.tool.BadInputException

/**
 * Shared by [ToolConnectOAuthProvider], [ToolCheckOAuthStatus], and [ToolSafeApiCall]: loads the
 * bundle fresh (never trusting a caller-supplied provider/manifest) and routes it through the same
 * [SkillApprovalGate] that [ToolGetSkillByName]/[ToolInvokeSkill] use before running or exposing a
 * file-backed skill. Without this, a stored-but-never-approved (or rejected) bundle declaring
 * `oauthProvider` could drive a real OAuth connection or API call. [approvalGate] must be a real,
 * request-scoped gate on any host that enforces approval at all — see the constructor docs on
 * [ToolConnectOAuthProvider]/[ToolCheckOAuthStatus]/[ToolSafeApiCall] for why a null default here
 * would silently disable that enforcement.
 */
internal suspend fun loadApprovedOAuthSkillBundle(
    skillBundleProvider: SkillBundleProvider,
    approvalGate: SkillApprovalGate?,
    userId: String,
    rawSkillId: String,
): SkillBundle {
    val skillId = SkillId(rawSkillId)
    val bundle = skillBundleProvider.loadSkillBundle(userId, skillId)
        ?: throw BadInputException("Skill is not available: $rawSkillId")
    val gate = approvalGate ?: return bundle
    return when (
        val approval = gate.ensureApproved(
            SkillApprovalGate.Input(userId = userId, skillId = skillId, bundle = bundle)
        )
    ) {
        is SkillApprovalGate.Result.Approved -> approval.bundle
        is SkillApprovalGate.Result.Rejected -> throw BadInputException(
            "Skill validation rejected for $rawSkillId: ${approval.reason}"
        )
    }
}
