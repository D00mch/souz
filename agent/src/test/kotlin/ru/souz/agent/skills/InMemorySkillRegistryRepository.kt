package ru.souz.agent.skills

import ru.souz.agent.skills.validation.SkillBundleHasher
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus
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

    override suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String?,
    ) {
        val key = ValidationKey(userId, skillId, bundleHash, policyVersion)
        val current = validations[key] ?: return
        validations[key] = current.copy(
            status = status,
            reasons = current.reasons + listOfNotNull(reason),
        )
    }

    override suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String?,
    ) {
        validations.entries
            .filter { (key, record) ->
                key.userId == userId &&
                        key.skillId == skillId &&
                        key.policyVersion == policyVersion &&
                        key.bundleHash != activeBundleHash &&
                        record.status == SkillValidationStatus.APPROVED
            }
            .forEach { (key, record) ->
                validations[key] = record.copy(
                    status = SkillValidationStatus.STALE,
                    reasons = record.reasons + listOfNotNull(reason),
                )
            }
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
