package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord

internal object BackendNoopSkillRegistryRepository : SkillRegistryRepository {
    override suspend fun listSkills(userId: String): List<StoredSkill> = emptyList()

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? = null

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? = null

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill =
        error("Skill registry repository is not configured for this backend runtime.")

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = null

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = null

    override suspend fun saveValidation(record: SkillValidationRecord) = Unit
}
