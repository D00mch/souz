package ru.souz.agent.skills.registry

import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus

/**
 * Combined persistence contract used by [ru.souz.agent.skills.SkillActivationPipeline].
 *
 * A single implementation owns both the user-visible skill catalog and the persisted
 * validation cache keyed by user, skill id, bundle hash, and policy version.
 */
interface SkillRegistryRepository {
    /** Returns metadata for every skill currently registered for the given user. */
    suspend fun listSkills(userId: String): List<StoredSkill>

    /** Looks up a registered skill by its canonical [SkillId]. */
    suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill?

    /** Looks up a registered skill by manifest name for UX flows that start from names. */
    suspend fun getSkillByName(userId: String, name: String): StoredSkill?

    /**
     * Stores or replaces the full bundle for a user-visible skill registration.
     *
     * Implementations should return the persisted metadata snapshot that selection UIs can
     * surface without loading the bundle again.
     */
    suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill

    /** Loads the exact bundle content needed for hashing, validation, and activation. */
    suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle?

    /**
     * Returns the cached validation snapshot for the exact bundle hash and policy version, or
     * `null` when the bundle has never been validated under that cache key.
     */
    suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord?

    /**
     * Persists the full validation outcome for a bundle.
     *
     * Callers use this after a complete validation pass so later activations can either reuse an
     * approved decision or block on a cached rejection without repeating the validators.
     */
    suspend fun saveValidation(record: SkillValidationRecord)

    /**
     * Updates only the stored lifecycle [status] and optional human-readable `reason`.
     */
    suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String? = null,
    )

    /**
     * Marks other cached validations for the same [userId], [skillId], and [policyVersion] as stale when their
     * bundle hash no longer matches `activeBundleHash`.
     */
    suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String? = null,
    )
}
