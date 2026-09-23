package ru.souz.llms.runtime

import com.fasterxml.jackson.databind.JsonNode
import ru.souz.jev.JevClient
import ru.souz.jev.JevNoulQuestion
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import ru.souz.tool.UserMessageClassifier

class JevClassifier(
    private val client: JevClient,
    private val threshold: Double = 0.5,
) : UserMessageClassifier {
    init {
        require(threshold.isFinite() && threshold in 0.0..1.0) { "JEV_THRESHOLD must be a number within [0, 1]" }
        client.requireConfigured()
    }

    override suspend fun classify(body: LLMRequest.Chat, categories: Map<ToolCategory, String>): UserMessageClassifier.Reply {
        if (categories.isEmpty()) return UserMessageClassifier.Reply(emptyList(), null)
        val state = restJsonMapper.valueToTree<JsonNode>(
            body.messages.filterNot { it.role == LLMMessageRole.system }
                .map { mapOf("role" to it.role.name, "content" to it.content) },
        )
        val questions = categories.entries.associate { (category, description) ->
            category.name to JevNoulQuestion(
                "Does fulfilling the latest user request require this tool category: ${category.name} ($description)? " +
                    "Consider every step of a combined request. Use history only to resolve missing context. " +
                    "Mentioning a topic alone does not require a tool. " +
                    "Understanding the current screen requires both DESKTOP capture and IMAGE analysis, " +
                    "unless a path to an existing image was supplied.",
            )
        }
        val result = client.evaluate(state, questions)
        return UserMessageClassifier.Reply(
            categories = categories.keys.filter { result.probabilities.getValue(it.name) > threshold },
            confidence = null,
        )
    }
}

fun configuredUserMessageClassifier(
    api: LLMChatAPI,
    jevClient: JevClient,
    environment: (String) -> String? = System::getenv,
): UserMessageClassifier = when (environment("SOUZ_CLASSIFIER")?.trim()?.lowercase().orEmpty()) {
    "", "llm" -> ApiClassifier(api)
    "jev" -> JevClassifier(
        jevClient,
        environment("JEV_THRESHOLD")?.let {
            requireNotNull(it.trim().toDoubleOrNull()) { "JEV_THRESHOLD must be a number within [0, 1]" }
        } ?: 0.5,
    )
    else -> error("SOUZ_CLASSIFIER must be llm or jev")
}
