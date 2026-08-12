package ru.souz.backend.common

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory
import ru.souz.tool.files.ToolGenerateImage
import ru.souz.tool.files.ToolViewImage
import ru.souz.tool.web.ToolInternetResearch
import ru.souz.tool.web.ToolInternetSearch

class BackendAvailableToolNamesTest {
    @Test
    fun `available names combine process tools and execution-bound LLM tools`() {
        val names = BackendAvailableToolNames.fromProcessCatalog(UnusedProcessCatalog).values

        assertContains(names, "ReadFile")
        assertContains(names, ToolInternetSearch.NAME)
        assertContains(names, ToolInternetResearch.NAME)
        assertContains(names, ToolViewImage.NAME)
        assertContains(names, ToolGenerateImage.NAME)
        assertEquals(5, names.size)
    }
}

private object UnusedProcessCatalog : AgentToolCatalog {
    override val toolsByCategory = mapOf(
        ToolCategory.FILES to mapOf(UnusedProcessTool.fn.name to UnusedProcessTool),
    )
}

private object UnusedProcessTool : LLMToolSetup {
    override val fn = LLMRequest.Function(name = "ReadFile", description = "Read a file")
    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        error("not invoked")
}
