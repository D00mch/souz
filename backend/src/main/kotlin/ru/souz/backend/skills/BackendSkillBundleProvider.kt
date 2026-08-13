package ru.souz.backend.skills

import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.backend.client.BackendClientSkills

/**
 * Combines the backend's two Skill sources. Immutable resources always win ID collisions with
 * tenant-scoped user registrations.
 */
internal class BackendSkillBundleProvider(
    private val resourceSkills: BackendClientSkills,
    private val userSkills: SkillRegistryRepository,
) : SkillBundleProvider {
    override suspend fun listSkills(userId: String): List<StoredSkill> {
        val byId = linkedMapOf<SkillId, StoredSkill>()
        resourceSkills.listSkills(userId).forEach { stored -> byId[stored.skillId] = stored }
        userSkills.listSkills(userId).forEach { stored -> byId.putIfAbsent(stored.skillId, stored) }
        return byId.values.sortedBy { it.skillId.value }
    }

    override suspend fun listSkillInventoryIds(userId: String): List<SkillId> =
        (resourceSkills.listSkillInventoryIds(userId) + userSkills.listSkillInventoryIds(userId))
            .distinct()
            .sortedBy { it.value }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? =
        resourceSkills.loadSkillBundle(userId, skillId)
            ?: userSkills.loadSkillBundle(userId, skillId)
}
