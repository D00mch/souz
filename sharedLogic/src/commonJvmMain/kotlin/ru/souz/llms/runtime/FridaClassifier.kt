package ru.souz.llms.runtime

import com.fasterxml.jackson.databind.node.TextNode
import ru.souz.agent.skills.SkillClassifier
import ru.souz.agent.skills.SkillId
import ru.souz.frida.FridaClient
import ru.souz.llms.LLMRequest
import ru.souz.tool.ToolCategory
import ru.souz.tool.UserMessageClassifier

/**
 * FRIDA-Decisions selection: one choice question per selection, read through raw margins. The top option is
 * selected, plus every option at least 3 above the mean margin; nothing is selected when the Skills' `none` wins.
 */
class FridaClassifier(private val client: FridaClient) : UserMessageClassifier, SkillClassifier {
    override suspend fun classify(body: LLMRequest.Chat, categories: Map<ToolCategory, String>): UserMessageClassifier.Reply {
        val options = categories.entries.associate { (category, description) -> category.name to description + screenRule(category) }
        val selected = selected(client.choose(TextNode(jevState(body.messages)), TOOLS_QUESTION, options))
        return UserMessageClassifier.Reply(categories = categories.keys.filter { it.name in selected }, confidence = null)
    }

    override suspend fun selectSkills(request: LLMRequest.Chat, descriptions: Map<SkillId, String>): Set<SkillId> {
        val options = descriptions.mapKeys { it.key.value }.filterKeys { it != NONE }
        if (options.isEmpty()) return emptySet()
        val state = TextNode(jevState(request.messages, historyLimit = 2))
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
        const val TOOLS_QUESTION = "Which tool category is needed to fulfil the latest user request? " +
            "Consider every step of a combined request. Use history only to resolve missing context. " +
            "Mentioning a topic alone does not require a tool."
        const val SKILLS_QUESTION = "Which Skill would help fulfil the new user message? " +
            "Use history only to resolve missing context."
        const val NONE = "none"
        const val NONE_DESCRIPTION = "none of the Skills is needed: the request is done without them or is about something else"
    }
}
