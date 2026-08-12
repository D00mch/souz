package ru.souz.agent.skills.registry

import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle

/**
 * Read-only source of user-visible Skill bundles.
 *
 * Hosts can back this with filesystem state, bundled resources, a database, or another storage
 * mechanism without forcing callers to depend on validation or mutation APIs.
 */
interface SkillBundleProvider {
    /** Returns metadata for every skill currently available for the given user. */
    suspend fun listSkills(userId: String): List<StoredSkill>

    /** Returns only opaque Skill IDs for prompt inventory. */
    suspend fun listSkillInventoryIds(userId: String): List<SkillId> =
        listSkills(userId).map { it.skillId }

    /** Loads the exact bundle content needed for hashing, validation, and execution. */
    suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle?
}

interface WritableSkillBundleProvider : SkillBundleProvider {
    /**
     * Stores or replaces the full bundle for a user-visible skill registration.
     *
     * Implementations return the persisted metadata snapshot that management UIs can surface
     * without loading the bundle again.
     */
    suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill
}
