package ru.souz.tool

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta

class ToolsFactory(
    runtimeToolCatalog: AgentToolCatalog,
    llmBackedToolCatalog: LlmBackedToolCatalog,
    desktopToolCatalog: AgentToolCatalog,
    settingsProvider: SettingsProvider,
) : AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>

    init {
        val composed = composeToolCatalogs(
            listOf(runtimeToolCatalog, llmBackedToolCatalog, desktopToolCatalog)
        )
        toolsByCategory = immutableToolCatalogSnapshot(
            ToolCategory.entries.associateWith { category ->
                composed.toolsByCategory.getValue(category).mapValues { (_, tool) ->
                    if (settingsProvider.useFewShotExamples) tool else tool.withoutFewShotExamples()
                }
            }
        ).toolsByCategory
    }
}

private fun LLMToolSetup.withoutFewShotExamples(): LLMToolSetup {
    val delegate = this
    return object : LLMToolSetup {
        override val fn: LLMRequest.Function = delegate.fn.copy(fewShotExamples = emptyList())

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            delegate.invoke(functionCall)

        override suspend fun invoke(
            functionCall: LLMResponse.FunctionCall,
            meta: ToolInvocationMeta,
        ): LLMRequest.Message = delegate.invoke(functionCall, meta)
    }
}
