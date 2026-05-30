package ru.souz.agent.skills.activation

import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest

internal object SkillContextInjector {
    const val START_MARKER = "<souz_skills_context>"
    const val END_MARKER = "</souz_skills_context>"

    private val SKILLS_CONTEXT_BLOCK = Regex(
        pattern = "\\s*${Regex.escape(START_MARKER)}.*?${Regex.escape(END_MARKER)}\\s*",
        option = RegexOption.DOT_MATCHES_ALL,
    )

    fun inject(
        context: AgentContext<String>,
        activatedSkills: List<ActivatedSkill>,
    ): AgentContext<String> {
        val updatedSystemPrompt = buildSystemPrompt(
            baseSystemPrompt = context.systemPrompt,
            activatedSkills = activatedSkills,
        )
        return context.map(history = context.history.replaceSystemPrompt(updatedSystemPrompt)) { it }
    }

    /** Walk through [context] history, find message with Skills instructions and remove it. */
    fun clear(context: AgentContext<String>): AgentContext<String> {
        val cleanSystemPrompt = context.systemPrompt.removeSkillsContext()
        val cleanHistory = context.history.mapIndexed { index, message ->
            if (index == 0 && message.role == LLMMessageRole.system) {
                message.copy(content = cleanSystemPrompt)
            } else {
                message
            }
        }

        return context.copy(
            systemPrompt = cleanSystemPrompt,
            history = cleanHistory,
        )
    }

    private fun String.removeSkillsContext(): String = replace(SKILLS_CONTEXT_BLOCK, "").trimEnd()

    private fun buildSystemPrompt(
        baseSystemPrompt: String,
        activatedSkills: List<ActivatedSkill>,
    ): String {
        if (activatedSkills.isEmpty()) return baseSystemPrompt // early exit

        val skillsContext = buildSkillsContext(activatedSkills)
        return buildString {
            if (baseSystemPrompt.isNotBlank()) {
                append(baseSystemPrompt.trimEnd())
                append("\n\n")
            }
            append(skillsContext)
        }
    }

    private fun List<LLMRequest.Message>.replaceSystemPrompt(systemPrompt: String): List<LLMRequest.Message> {
        if (isEmpty()) return emptyList()                      // early exit
        val systemMessage = LLMRequest.Message(
            role = LLMMessageRole.system,
            content = systemPrompt,
        )
        return listOf(systemMessage) + drop(1)
    }

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
