package ru.souz.backend.agent.runtime.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.backend.testutil.TestToolCatalog
import ru.souz.backend.testutil.testToolCatalog
import ru.souz.backend.testutil.testToolSetup
import ru.souz.llms.LLMRequest
import ru.souz.tool.LLM_BACKED_TOOL_NAMES
import ru.souz.tool.ToolCategory
import ru.souz.tool.web.ToolWebImageSearch

class BackendExecutionToolCatalogTest {
    @Test
    fun `execution catalog drops tools the backend capability policy excludes`() {
        val executionTools = toolNames(executionCatalog(enabledCompiledToolNames = null))

        assertFalse("ControlBrowser" in executionTools)
        assertFalse(ToolWebImageSearch.NAME in executionTools)
        assertEquals(setOf("ReadFile", "ClientAsk") + LLM_BACKED_TOOL_NAMES, executionTools)
    }

    @Test
    fun `client tools merge after compiled selection and survive an enabled snapshot`() {
        val executionTools = toolNames(executionCatalog(enabledCompiledToolNames = setOf("ReadFile")))

        assertEquals(setOf("ReadFile", "ClientAsk"), executionTools)
    }

    @Test
    fun `client tools win name collisions with compiled tools`() {
        val catalog = BackendExecutionToolCatalog(
            compiledToolCatalog = testToolCatalog(ToolCategory.FILES to listOf("ReadFile")),
            executionLlmToolCatalog = testToolCatalog(),
            enabledCompiledToolNames = null,
            clientToolCatalog = TestToolCatalog(
                mapOf(
                    ToolCategory.FILES to mapOf(
                        "ReadFile" to testToolSetup("ReadFile", description = "client owned"),
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
        val catalog = BackendExecutionToolCatalog(
            compiledToolCatalog = TestToolCatalog(
                mapOf(
                    ToolCategory.FILES to mapOf(
                        "ReadFile" to testToolSetup(
                            name = "ReadFile",
                            fewShotExamples = listOf(LLMRequest.FewShotExample("read it", emptyMap())),
                        ),
                    ),
                )
            ),
            executionLlmToolCatalog = testToolCatalog(),
            enabledCompiledToolNames = null,
            clientToolCatalog = testToolCatalog(),
            includeFewShotExamples = false,
        )

        assertEquals(
            emptyList(),
            catalog.toolsByCategory.getValue(ToolCategory.FILES).getValue("ReadFile").fn.fewShotExamples,
        )
    }

    private fun executionCatalog(enabledCompiledToolNames: Set<String>?): BackendExecutionToolCatalog =
        BackendExecutionToolCatalog(
            compiledToolCatalog = testToolCatalog(
                ToolCategory.FILES to listOf("ReadFile"),
                ToolCategory.WEB_SEARCH to listOf(ToolWebImageSearch.NAME),
                ToolCategory.BROWSER to listOf("ControlBrowser"),
            ),
            executionLlmToolCatalog = testToolCatalog(
                ToolCategory.WEB_SEARCH to BackendToolCapabilityPolicy.executionBoundToolNames.toList(),
            ),
            enabledCompiledToolNames = enabledCompiledToolNames,
            clientToolCatalog = testToolCatalog(ToolCategory.CHAT to listOf("ClientAsk")),
            includeFewShotExamples = true,
        )

    private fun toolNames(catalog: AgentToolCatalog): Set<String> =
        catalog.toolsByCategory.values.flatMapTo(linkedSetOf()) { tools -> tools.keys }
}
