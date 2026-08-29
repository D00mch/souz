package ru.souz.backend.testutil

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

/** Catalog of inert tools that only carry their identity, for capability and selection tests. */
internal class TestToolCatalog(
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
) : AgentToolCatalog {
    constructor(vararg categories: Pair<ToolCategory, List<String>>) : this(
        categories.associate { (category, toolNames) ->
            category to toolNames.associateWith { toolName -> TestToolSetup(toolName) }
        }
    )
}

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
