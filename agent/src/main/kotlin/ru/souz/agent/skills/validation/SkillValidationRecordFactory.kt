package ru.souz.agent.skills.validation

import ru.souz.agent.skills.activation.SkillId
import java.time.Instant

object SkillValidationRecordFactory {
    fun build(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policy: SkillValidationPolicy,
        structural: SkillValidationResult,
        static: SkillValidationResult,
        llm: SkillLlmValidationVerdict,
        createdAt: Instant,
    ): SkillValidationRecord {
        val allFindings = structural.findings + static.findings + llm.findings
        val approved = !structural.hasHardReject &&
            !static.hasHardReject &&
            llm.decision == SkillLlmValidationDecision.APPROVE &&
            llm.confidence >= policy.minApprovalConfidence

        return SkillValidationRecord(
            userId = userId,
            skillId = skillId,
            bundleHash = bundleHash,
            status = if (approved) SkillValidationStatus.APPROVED else SkillValidationStatus.REJECTED,
            policyVersion = policy.policyVersion,
            validatorVersion = policy.validatorVersion,
            model = llm.model,
            reasons = llm.reasons,
            findings = allFindings,
            createdAt = createdAt,
        )
    }
}
