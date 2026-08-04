package ru.souz.agent.skills.validation

import ru.souz.agent.skills.activation.SkillId

/**
 * Cache for validation outcomes keyed by user, skill, bundle hash, and policy version.
 */
interface SkillValidationStore {
    suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord?

    suspend fun saveValidation(record: SkillValidationRecord)
}
