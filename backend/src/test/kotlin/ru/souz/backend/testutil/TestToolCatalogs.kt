package ru.souz.backend.testutil

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

/** Catalog of inert tools that only carry their identity, for capability and selection tests. */
internal fun TestToolCatalog(
    toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
): AgentToolCatalog = AgentToolCatalog(toolsByCategory.mapValues { it.value.values })

internal fun TestToolCatalog(vararg categories: Pair<ToolCategory, List<String>>): AgentToolCatalog = AgentToolCatalog(
    categories.associate { (category, names) -> category to names.map { TestToolSetup(it) } }
)

internal class TestToolSetup(
    name: String,
    description: String = "$name description",
    fewShotExamples: List<LLMRequest.FewShotExample>? = null,
) : LLMToolSetup {
    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = name,
        description = description,
        fewShotExamples = fewShotExamples,
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        LLMRequest.Message(LLMMessageRole.function, "ok")
}
