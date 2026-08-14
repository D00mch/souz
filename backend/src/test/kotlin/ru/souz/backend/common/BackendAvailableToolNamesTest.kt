package ru.souz.backend.common

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.LLM_BACKED_TOOL_NAMES
import ru.souz.tool.ToolCategory

class BackendAvailableToolNamesTest {
    @Test
    fun `available names combine process tools and execution-bound LLM tools`() {
        val names = BackendAvailableToolNames.fromProcessCatalog(UnusedProcessCatalog).values

        assertEquals(setOf("ReadFile") + LLM_BACKED_TOOL_NAMES, names)
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
