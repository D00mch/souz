package ru.souz.agent.skills.registry

import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.activation.SkillId

/**
 * Storage abstraction for registered skill bundles. Registered skills are user-scoped.
 */
interface SkillsRepository {
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
}
