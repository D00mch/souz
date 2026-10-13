package ru.souz.backend.agent.runtime.conversation

import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.backend.metrics.BackendMetrics
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.composeToolCatalogs
import ru.souz.tool.immutableToolCatalogSnapshot
import ru.souz.tool.withoutFewShotExamples

/** Immutable tools available to one backend execution. Compiled-tool selection precedes client-tool merging. */
internal fun backendExecutionToolCatalog(
    compiledToolCatalog: AgentToolCatalog,
    executionLlmToolCatalog: AgentToolCatalog,
    enabledCompiledToolNames: Set<String>?,
    clientToolCatalog: AgentToolCatalog,
    includeFewShotExamples: Boolean,
    metrics: BackendMetrics? = null,
): AgentToolCatalog {
    val selectedCompiledTools = BackendToolCapabilityPolicy.selectExecutionTools(
        processToolCatalog = compiledToolCatalog,
        executionLlmToolCatalog = executionLlmToolCatalog,
        enabledToolNames = enabledCompiledToolNames,
    )

    val mergedTools = composeToolCatalogs(
        if (metrics == null) selectedCompiledTools else immutableToolCatalogSnapshot(
            selectedCompiledTools.toolsByCategory.mapValues { (category, tools) ->
                tools.mapValues { (_, tool) -> metrics.instrumentTool(tool, category) }
            }
        ),
        clientToolCatalog,
    )
    if (includeFewShotExamples) {
        return mergedTools
    }
    return immutableToolCatalogSnapshot(
        mergedTools.toolsByCategory.mapValues { (_, tools) ->
            tools.mapValues { (_, tool) -> tool.withoutFewShotExamples() }
        }
    )
}

/** Direct child calls need an advertised routing argument, even for closed client schemas. */
internal fun backendSubagentToolCatalog(
    executionToolCatalog: AgentToolCatalog,
    clientToolNames: Set<String>,
): AgentToolCatalog = immutableToolCatalogSnapshot(
    executionToolCatalog.toolsByCategory.mapValues { (_, tools) ->
        tools.mapValues { (name, tool) ->
            if (name !in clientToolNames) tool else object : LLMToolSetup by tool {
                override val fn = tool.fn.copy(parameters = tool.fn.parameters.copy(
                    properties = tool.fn.parameters.properties + ("channelId" to LLMRequest.Property(
                        type = "string",
                        description = "Optional backend routing override. Omit to use the current client device. " +
                            "For another device or no current client, use the connected device channel UUID " +
                            "provided by the parent or returned by ListActiveChannels if that tool is selected. " +
                            "Otherwise ask the parent for a channelId. Removed before device dispatch.",
                    )),
                ))
            }
        }
    },
)
