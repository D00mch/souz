package ru.souz.skill

import ru.souz.agent.skill.AgentSkill
import ru.souz.agent.skill.AgentSkillMatch
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.db.StorredData

class SkillAwareAgentDesktopInfoRepository(
    private val skillCatalog: FilesystemSkillCatalog,
    private val localSearch: suspend (query: String, limit: Int) -> List<StorredData> = { _, _ -> emptyList() },
) : AgentDesktopInfoRepository {
    override suspend fun search(query: String, limit: Int): List<StorredData> = localSearch(query, limit)

    override suspend fun searchSkills(query: String, limit: Int): List<AgentSkillMatch> =
        skillCatalog.searchRelevantSkills(query, limit)

    override suspend fun loadSkill(name: String): AgentSkill? = skillCatalog.loadSkill(name)
}
