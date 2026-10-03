package ru.souz.agent.skills

import ru.souz.llms.LLMRequest

/** Selects exact IDs from discovery metadata without loading Skill bundles. */
fun interface SkillClassifier {
    suspend fun selectSkills(request: LLMRequest.Chat, descriptions: Map<SkillId, String>): Set<SkillId>
}
