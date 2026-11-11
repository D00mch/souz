package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.backend.llm.quota.ExecutionQuotaManager
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolViewImage
import ru.souz.tool.withoutFewShotExamples

/** Immutable tools available to one backend execution. Compiled-tool selection precedes client-tool merging. */
internal fun backendExecutionToolCatalog(
    executionQuotas: ExecutionQuotaManager,
    compiledToolCatalog: AgentToolCatalog,
    executionLlmToolCatalog: AgentToolCatalog,
    enabledCompiledToolNames: Set<String>?,
    clientToolCatalog: AgentToolCatalog,
    includeFewShotExamples: Boolean,
    metrics: BackendMetrics? = null,
    pluginToolCatalog: AgentToolCatalog = AgentToolCatalog(),
): AgentToolCatalog {
    val selectedCompiledTools = BackendToolCapabilityPolicy.selectExecutionTools(
        processToolCatalog = compiledToolCatalog,
        executionLlmToolCatalog = executionLlmToolCatalog,
        enabledToolNames = enabledCompiledToolNames,
        pluginToolCatalog = pluginToolCatalog,
    ).mapTools { _, tool ->
        if (tool.fn.name != ToolViewImage.NAME && tool.fn.name != ToolGenerateImage.NAME) tool
        else object : LLMToolSetup by tool {
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall) =
                invoke(functionCall, ToolInvocationMeta.localDefault())

            override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta) =
                // Include sandbox reads, provider admission/retries, output writes and cancellation cleanup.
                executionQuotas.withImageProjectionPermit { tool.invoke(functionCall, meta) }
        }
    }

    val mergedTools = AgentToolCatalog.compose(
        if (metrics == null) selectedCompiledTools else selectedCompiledTools.mapTools { category, tool ->
            metrics.instrumentTool(tool, category)
        },
        clientToolCatalog,
    )
    if (includeFewShotExamples) {
        return mergedTools
    }
    return mergedTools.mapTools { _, tool -> tool.withoutFewShotExamples() }
}
