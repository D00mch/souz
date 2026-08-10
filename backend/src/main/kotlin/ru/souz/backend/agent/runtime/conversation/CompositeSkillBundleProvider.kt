package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillBundleProvider
import ru.souz.agent.skills.registry.StoredSkill

/** Read-only request view over ordered Skill providers. Earlier providers win ID collisions. */
internal class CompositeSkillBundleProvider(
    providers: List<SkillBundleProvider>,
) : SkillBundleProvider {
    private val providers = providers.toList()

    init {
        require(this.providers.isNotEmpty()) { "At least one Skill bundle provider is required." }
    }

    override suspend fun listSkills(userId: String): List<StoredSkill> {
        val skillsById = linkedMapOf<SkillId, StoredSkill>()
        providers.forEach { provider ->
            provider.listSkills(userId).forEach { skill ->
                skillsById.putIfAbsent(skill.skillId, skill)
            }
        }
        return skillsById.values.toList()
    }

    override suspend fun listSkillInventoryIds(userId: String): List<SkillId> {
        val skillIds = linkedSetOf<SkillId>()
        providers.forEach { provider ->
            skillIds += provider.listSkillInventoryIds(userId)
        }
        return skillIds.toList()
    }

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? {
        providers.forEach { provider ->
            provider.loadSkillBundle(userId, skillId)?.let { return it }
        }
        return null
    }
}
