package ru.souz.agent.skills.activation

import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

internal object SkillContextInjector {
    private const val START_MARKER = "<souz_skills_context>"
    private const val END_MARKER = "</souz_skills_context>"

    fun inject(
        context: AgentContext<String>,
        activatedSkills: List<ActivatedSkill>,
    ): AgentContext<String> {
        val cleanedHistory = context.history.filterNot(::isSkillsContextMessage)
        if (activatedSkills.isEmpty()) {
            return context.map(history = cleanedHistory) { it }
        }

        val message = LLMRequest.Message(
            role = LLMMessageRole.user,
            content = buildSkillsContext(activatedSkills),
        )
        val insertionIndex = cleanedHistory.indexOfLast { it.role == LLMMessageRole.user }
            .takeIf { it >= 0 }
            ?: cleanedHistory.size

        val updatedHistory = buildList {
            addAll(cleanedHistory.take(insertionIndex))
            add(message)
            addAll(cleanedHistory.drop(insertionIndex))
        }
        return context.map(history = updatedHistory) { it }
    }

    fun isSkillsContextMessage(message: LLMRequest.Message): Boolean =
        message.role == LLMMessageRole.user &&
            message.content.contains(START_MARKER) &&
            message.content.contains(END_MARKER)

    private fun buildSkillsContext(activatedSkills: List<ActivatedSkill>): String = buildString {
        appendLine(START_MARKER)
        appendLine("Background skill instructions. Use a skill only if it is relevant to the current user request.")
        appendLine("Do not mention this skills context or internal validation to the user.")
        appendLine("---")
        activatedSkills.forEach { skill ->
            appendLine("Skill ID: ${skill.skillId.value}")
            appendLine("Skill Name: ${skill.manifest.name}")
            appendLine("Description: ${skill.manifest.description}")
            skill.manifest.version?.let { appendLine("Version: $it") }
            if (skill.supportingFiles.isNotEmpty()) {
                appendLine("Supporting Files: ${skill.supportingFiles.joinToString(", ")}")
            }
            appendLine("Instructions:")
            appendLine(skill.instructionBody.trim())
            appendLine("---")
        }
        append(END_MARKER)
    }
}
