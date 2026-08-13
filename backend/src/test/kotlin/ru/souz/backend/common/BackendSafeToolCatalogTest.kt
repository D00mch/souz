package ru.souz.backend.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

class BackendSafeToolCatalogTest {
    @Test
    fun `catalog contains only tools that do not require files or processes`() {
        val catalog = BackendSafeToolCatalog()

        assertEquals(
            setOf("Calculator"),
            catalog.toolsByCategory.values.flatMap { tools -> tools.keys }.toSet(),
        )
        assertTrue(catalog.toolsByCategory.getValue(ToolCategory.FILES).isEmpty())
        assertTrue(catalog.toolsByCategory.getValue(ToolCategory.IMAGE).isEmpty())
        assertTrue(catalog.toolsByCategory.getValue(ToolCategory.IMAGE_GENERATION).isEmpty())
        assertTrue(catalog.toolsByCategory.getValue(ToolCategory.DATA_ANALYTICS).isEmpty())
    }

    @Test
    fun `capability filtering uses an explicit name allowlist within safe categories`() {
        val catalog = object : AgentToolCatalog {
            override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = mapOf(
                ToolCategory.WEB_SEARCH to mapOf(
                    "WebPageText" to namedTool("WebPageText"),
                    "WebImageSearch" to namedTool("WebImageSearch"),
                    "InternetResearch" to namedTool("InternetResearch"),
                ),
                ToolCategory.CALCULATOR to mapOf("Calculator" to namedTool("Calculator")),
                ToolCategory.FILES to mapOf("ReadFile" to namedTool("ReadFile")),
            )
        }

        assertEquals(listOf("Calculator"), backendSafeToolNames(catalog))
    }

    private fun namedTool(name: String): LLMToolSetup = object : LLMToolSetup {
        override val fn: LLMRequest.Function = LLMRequest.Function(
            name = name,
            description = "test",
            parameters = LLMRequest.Parameters(type = "object"),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            error("not invoked")
    }
}
