package ru.souz.agent.skills.selection

import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.souz.agent.skills.bundle.SkillBundleException
import ru.souz.agent.skills.activation.SkillId
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.json.JsonUtils
import ru.souz.llms.restJsonMapper

fun interface SkillSelector {
    suspend fun select(input: SkillSelectionInput): SkillSelectionResult
}

class LlmSkillSelector(
    private val llmApi: LLMChatAPI,
    private val model: String,
    private val jsonUtils: JsonUtils,
) : SkillSelector {
    private val logger = LoggerFactory.getLogger(LlmSkillSelector::class.java)

    override suspend fun select(input: SkillSelectionInput): SkillSelectionResult {
        if (input.availableSkills.isEmpty()) {
            return SkillSelectionResult(emptyList(), "No skills available.")
        }

        val prompt = buildPrompt(input)
        val response = llmApi.message(
            LLMRequest.Chat(
                model = model,
                temperature = 0.0f,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.system,
                        content = SELECTOR_SYSTEM_PROMPT,
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = prompt,
                    ),
                ),
            )
        )

        val ok = response as? LLMResponse.Chat.Ok
            ?: throw SkillBundleException("Skill selector LLM request failed: $response")
        val content = ok.choices.lastOrNull()?.message?.content.orEmpty()
        val parsed: SelectorResponse = restJsonMapper.readValue(jsonUtils.extractObject(content))
        logger.info(
            "Skill selector returned {} candidate(s) for {} available skill(s)",
            parsed.selectedSkillIds.size,
            input.availableSkills.size,
        )
        return SkillSelectionResult(
            selectedSkillIds = parsed.selectedSkillIds.map(::SkillId).distinct(),
            rationale = parsed.rationale.orEmpty(),
        )
    }

    private fun buildPrompt(input: SkillSelectionInput): String = buildString {
        appendLine("User request:")
        appendLine(input.userMessage)
        appendLine()
        appendLine("Available skills:")
        input.availableSkills.forEach { skill ->
            appendLine("- id=${skill.skillId.value}")
            appendLine("  name=${skill.manifest.name}")
            appendLine("  description=${skill.manifest.description}")
            skill.manifest.author?.let { appendLine("  author=$it") }
            skill.manifest.version?.let { appendLine("  version=$it") }
        }
    }

    private data class SelectorResponse(
        val selectedSkillIds: List<String> = emptyList(),
        val rationale: String? = null,
    )

    private companion object {
        private val SELECTOR_SYSTEM_PROMPT = """
            You are a strict skill selector for a desktop AI assistant.
            Return JSON only with this shape:
            {"selectedSkillIds":["skill-id"],"rationale":"short reason"}
            Rules:
            - You may select zero skills.
            - Select a skill only when the user request clearly benefits from that skill.
            - Never invent skill ids.
            - Use only the available skill metadata provided by the user message.
            - If unsure, return an empty list.
        """.trimIndent()
    }
}
