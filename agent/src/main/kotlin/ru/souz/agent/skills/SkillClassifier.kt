package ru.souz.agent.skills

import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

/** Selects exact Skill IDs from discovery descriptions using a typed, host-routed request. */
fun interface SkillClassifier {
    suspend fun selectSkills(request: LLMRequest.Chat, descriptions: Map<SkillId, String>): Set<SkillId>
}

internal class LlmSkillClassifier(private val api: LLMChatAPI) : SkillClassifier {
    override suspend fun selectSkills(request: LLMRequest.Chat, descriptions: Map<SkillId, String>): Set<SkillId> {
        val response = api.message(request) as? LLMResponse.Chat.Ok
            ?: error("Skill classification request failed")
        val content = response.choices.firstOrNull()?.message?.content
            ?: error("Skill classification response is empty")
        val ids = restJsonMapper.readTree(content)
        check(ids != null && ids.isArray && ids.all { it.isTextual }) {
            "Skill classification must return a JSON array of exact Skill IDs"
        }
        return ids.map { SkillId(it.asText()) }.filterTo(mutableSetOf()) { it in descriptions }
    }
}
