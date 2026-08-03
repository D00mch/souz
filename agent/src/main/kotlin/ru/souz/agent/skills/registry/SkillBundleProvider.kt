package ru.souz.agent.skills.registry

import ru.souz.agent.skills.activation.SkillId
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

    /** Returns metadata-only entries for compact discovery inventory. */
    suspend fun listSkillInventory(userId: String): List<SkillInventoryEntry> =
        listSkills(userId).map { it.toInventoryEntry() }

    /** Returns only opaque Skill IDs for prompt inventory. */
    suspend fun listSkillInventoryIds(userId: String): List<SkillId> =
        listSkillInventory(userId).map { it.skillId }

    /** Looks up a skill by its canonical [SkillId]. */
    suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill?

    /** Looks up a skill by manifest name for UX flows that start from names. */
    suspend fun getSkillByName(userId: String, name: String): StoredSkill?

    /** Loads the exact bundle content needed for hashing, validation, and activation. */
    suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle?
}

interface WritableSkillBundleProvider : SkillBundleProvider {
    /**
     * Stores or replaces the full bundle for a user-visible skill registration.
     *
     * Implementations return the persisted metadata snapshot that selection UIs can surface
     * without loading the bundle again.
     */
    suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill
}
