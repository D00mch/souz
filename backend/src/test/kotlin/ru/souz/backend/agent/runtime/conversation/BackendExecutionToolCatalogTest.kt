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
    fun `catalog filters compiled tools preserves client tools and selects the search provider`() {
        val selections = mapOf(
            null to (setOf("ReadFile", "WebPageText") + LLM_BACKED_TOOL_NAMES),
            setOf("ReadFile") to setOf("ReadFile"),
            setOf("InternetSearch") to setOf("InternetSearch"),
        )
        for ((enabled, compiled) in selections) {
            for (clientSearch in listOf(false, true)) {
                val expected = (if (clientSearch) compiled - "InternetSearch" else compiled) + setOf("ClientAsk", "web.search")
                assertEquals(expected, toolNames(executionCatalog(enabled, clientSearch)), "enabled=$enabled, clientSearch=$clientSearch")
            }
        }
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

    private fun executionCatalog(enabledCompiledToolNames: Set<String>?, clientSearch: Boolean): AgentToolCatalog =
        backendExecutionToolCatalog(
            compiledToolCatalog = TestToolCatalog(
                ToolCategory.FILES to listOf("ReadFile"),
                ToolCategory.WEB_SEARCH to listOf("WebPageText", ToolWebImageSearch.NAME),
                ToolCategory.BROWSER to listOf("ControlBrowser"),
            ),
            executionLlmToolCatalog = TestToolCatalog(
                ToolCategory.WEB_SEARCH to BackendToolCapabilityPolicy.executionBoundToolNames.toList(),
            ),
            enabledCompiledToolNames = enabledCompiledToolNames,
            clientToolCatalog = TestToolCatalog(
                ToolCategory.CHAT to listOf("ClientAsk"),
                ToolCategory.WEB_SEARCH to listOf("web.search"),
            ),
            includeFewShotExamples = true,
            clientSearchEnabled = clientSearch,
        )

    private fun toolNames(catalog: AgentToolCatalog): Set<String> =
        catalog.toolsByCategory.values.flatMapTo(linkedSetOf()) { tools -> tools.keys }
}
