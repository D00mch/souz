package ru.souz.agent.skills

import ru.souz.agent.skills.validation.SkillValidationRepository

interface SkillsRepository {
    suspend fun listSkills(userId: String): List<StoredSkill>
    suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill?
    suspend fun getSkillByName(userId: String, name: String): StoredSkill?
    suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill
    suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle?
}

interface SkillRegistryRepository : SkillsRepository, SkillValidationRepository
