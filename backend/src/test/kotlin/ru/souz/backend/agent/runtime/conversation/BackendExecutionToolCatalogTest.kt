package ru.souz.backend.agent.runtime.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.backend.testutil.TestToolCatalog
import ru.souz.backend.testutil.TestToolSetup
import ru.souz.llms.LLMRequest
import ru.souz.tool.LLM_BACKED_TOOL_NAMES
import ru.souz.tool.ToolCategory
import ru.souz.tool.web.ToolWebImageSearch

class BackendExecutionToolCatalogTest {
    @Test
    fun `execution catalog selects compiled tools through the backend capability policy`() {
        val executionTools = toolNames(executionCatalog(enabledCompiledToolNames = null))

        assertEquals(setOf("ReadFile", "ClientAsk") + LLM_BACKED_TOOL_NAMES, executionTools)
    }

    @Test
    fun `client tools merge after compiled selection and survive an enabled snapshot`() {
        val executionTools = toolNames(executionCatalog(enabledCompiledToolNames = setOf("ReadFile")))

        assertEquals(setOf("ReadFile", "ClientAsk"), executionTools)
    }

    @Test
    fun `client tools win name collisions with compiled tools`() {
        val catalog = backendExecutionToolCatalog(
            compiledToolCatalog = TestToolCatalog(ToolCategory.FILES to listOf("ReadFile")),
            executionLlmToolCatalog = TestToolCatalog(),
            enabledCompiledToolNames = null,
            clientToolCatalog = TestToolCatalog(
                mapOf(
                    ToolCategory.FILES to mapOf(
                        "ReadFile" to TestToolSetup("ReadFile", description = "client owned"),
                    ),
                )
            ),
            includeFewShotExamples = true,
        )

        assertEquals(
            "client owned",
            catalog.toolsByCategory.getValue(ToolCategory.FILES).getValue("ReadFile").fn.description,
        )
    }

    @Test
    fun `few-shot examples are stripped when the execution disables them`() {
        val catalog = backendExecutionToolCatalog(
            compiledToolCatalog = TestToolCatalog(
                mapOf(
                    ToolCategory.FILES to mapOf(
                        "ReadFile" to TestToolSetup(
                            name = "ReadFile",
                            fewShotExamples = listOf(LLMRequest.FewShotExample("read it", emptyMap())),
                        ),
                    ),
                )
            ),
            executionLlmToolCatalog = TestToolCatalog(),
            enabledCompiledToolNames = null,
            clientToolCatalog = TestToolCatalog(),
            includeFewShotExamples = false,
        )

        assertEquals(
            emptyList(),
            catalog.toolsByCategory.getValue(ToolCategory.FILES).getValue("ReadFile").fn.fewShotExamples,
        )
    }

    private fun executionCatalog(enabledCompiledToolNames: Set<String>?): AgentToolCatalog =
        backendExecutionToolCatalog(
            compiledToolCatalog = TestToolCatalog(
                ToolCategory.FILES to listOf("ReadFile"),
                ToolCategory.WEB_SEARCH to listOf(ToolWebImageSearch.NAME),
                ToolCategory.BROWSER to listOf("ControlBrowser"),
            ),
            executionLlmToolCatalog = TestToolCatalog(
                ToolCategory.WEB_SEARCH to BackendToolCapabilityPolicy.executionBoundToolNames.toList(),
            ),
            enabledCompiledToolNames = enabledCompiledToolNames,
            clientToolCatalog = TestToolCatalog(ToolCategory.CHAT to listOf("ClientAsk")),
            includeFewShotExamples = true,
        )

    private fun toolNames(catalog: AgentToolCatalog): Set<String> =
        catalog.toolsByCategory.values.flatMapTo(linkedSetOf()) { tools -> tools.keys }
}
