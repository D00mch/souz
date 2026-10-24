package ru.souz.llms.runtime

import ru.souz.agent.skills.SkillClassifier
import ru.souz.agent.skills.SkillId
import ru.souz.frida.FridaClient
import ru.souz.llms.LLMRequest

/**
 * FRIDA-Decisions selection: one choice question per selection, read through raw margins. The top option is
 * selected, plus every option at least 3 above the mean margin; nothing is selected when the Skills' `none` wins.
 */
class FridaClassifier(private val client: FridaClient) : SkillClassifier {
    override suspend fun selectSkills(request: LLMRequest.Chat, descriptions: Map<SkillId, String>): Set<SkillId> {
        val options = descriptions.mapKeys { it.key.value }.filterKeys { it != NONE }
        if (options.isEmpty()) return emptySet()
        val state = jevState(request.messages, historyLimit = 2)
        val selected = selected(client.choose(state, SKILLS_QUESTION, options + (NONE to NONE_DESCRIPTION)))
        return descriptions.keys.filterTo(mutableSetOf()) { it.value in selected }
    }

    private fun selected(margins: Map<String, Double>): Set<String> {
        val top = margins.maxByOrNull { it.value }?.key
        if (top == null || top == NONE) return emptySet()
        val mean = margins.values.average()
        return margins.filterValues { it - mean >= 3 }.keys + top - NONE
    }

    private companion object {
        const val SKILLS_QUESTION = "Which Skill would help fulfil the new user message? " +
            "Use history only to resolve missing context."
        const val NONE = "none"
        const val NONE_DESCRIPTION = "none of the Skills is needed: the request is done without them or is about something else"
    }
}
