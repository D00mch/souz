package ru.souz.agent.skills.implementations.registry

import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord
import java.time.Instant

class InMemorySkillRegistryRepository : SkillRegistryRepository {
    private val skills = linkedMapOf<Pair<String, SkillId>, SkillBundle>()
    private val validations = linkedMapOf<ValidationKey, SkillValidationRecord>()

    override suspend fun listSkills(userId: String): List<StoredSkill> = skills.entries
        .filter { it.key.first == userId }
        .map { (_, bundle) -> bundle.toStoredSkill(userId) }

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? =
        skills[userId to skillId]?.toStoredSkill(userId)

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        skills.entries
            .firstOrNull { (key, bundle) -> key.first == userId && bundle.manifest.name == name }
            ?.value
            ?.toStoredSkill(userId)

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill {
        skills[userId to bundle.skillId] = bundle
        return bundle.toStoredSkill(userId)
    }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = skills[userId to skillId]

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = validations[ValidationKey(userId, skillId, bundleHash, policyVersion)]

    override suspend fun saveValidation(record: SkillValidationRecord) {
        validations[ValidationKey(record.userId, record.skillId, record.bundleHash, record.policyVersion)] = record
    }

    private fun SkillBundle.toStoredSkill(userId: String): StoredSkill = StoredSkill(
        userId = userId,
        skillId = skillId,
        manifest = manifest,
        bundleHash = SkillBundleHasher.hash(this),
        createdAt = Instant.EPOCH,
    )

    private data class ValidationKey(
        val userId: String,
        val skillId: SkillId,
        val bundleHash: String,
        val policyVersion: String,
    )
}
