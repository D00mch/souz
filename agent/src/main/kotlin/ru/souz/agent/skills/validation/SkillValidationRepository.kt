package ru.souz.agent.skills.validation

import ru.souz.agent.skills.SkillId

interface SkillValidationRepository {
    suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord?

    suspend fun saveValidation(record: SkillValidationRecord)

    suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String? = null,
    )

    suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String? = null,
    )
}
