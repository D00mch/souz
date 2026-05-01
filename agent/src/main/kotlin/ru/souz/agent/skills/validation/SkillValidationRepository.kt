package ru.souz.agent.skills.validation

import ru.souz.agent.skills.activation.SkillId

/**
 * Persistence contract for the per-user skill validation cache.
 *
 * Records are keyed by the tuple of `userId`, [skillId], `bundleHash`, and `policyVersion`.
 * This lets the activation pipeline reuse prior validation decisions for the exact bundle
 * contents evaluated under a specific validation policy, while forcing revalidation whenever
 * the bundle changes or the policy version is bumped.
 */
interface SkillValidationRepository {
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
     *
     * This is intended for cache maintenance operations such as downgrading older records to
     * [SkillValidationStatus.STALE] without rebuilding the full validation snapshot.
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
     *
     * This keeps the cache aligned with
     * [ru.souz.agent.skills.SkillActivationPhase.CHECK_CACHE]: the current bundle may reuse its
     * exact cached record, while older approvals or rejections for previous bundle contents should
     * no longer be treated as authoritative for future activations.
     */
    suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String? = null,
    )
}
