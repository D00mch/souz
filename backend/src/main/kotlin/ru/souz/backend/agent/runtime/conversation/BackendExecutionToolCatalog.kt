package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolCategory

/** Immutable tools available to one backend execution. Compiled-tool selection precedes client-tool merging. */
internal class BackendExecutionToolCatalog(
    compiledToolCatalog: AgentToolCatalog,
    enabledCompiledToolNames: Set<String>?,
    clientToolCatalog: AgentToolCatalog,
    includeFewShotExamples: Boolean,
) : AgentToolCatalog {
    val alwaysAvailableToolNames: Set<String>

    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>

    init {
        val enabledToolNames = enabledCompiledToolNames?.toSet()
        val selectedCompiledToolsByCategory = compiledToolCatalog.toolsByCategory.mapValues { (_, tools) ->
            enabledToolNames?.let { enabled ->
                tools.filterKeys { toolName -> toolName in enabled }
            } ?: tools
        }
        val clientToolsByCategory = clientToolCatalog.toolsByCategory

        alwaysAvailableToolNames = clientToolsByCategory.values
            .flatMapTo(linkedSetOf()) { tools -> tools.keys }
            .toSet()

        toolsByCategory = (selectedCompiledToolsByCategory.keys + clientToolsByCategory.keys)
            .associateWith { category ->
                val mergedTools = selectedCompiledToolsByCategory[category].orEmpty() +
                    clientToolsByCategory[category].orEmpty()
                if (includeFewShotExamples) {
                    mergedTools
                } else {
                    mergedTools.mapValues { (_, tool) -> tool.withoutFewShotExamples() }
                }
            }
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
