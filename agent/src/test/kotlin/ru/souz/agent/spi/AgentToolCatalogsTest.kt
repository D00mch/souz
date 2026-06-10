package ru.souz.agent.spi

import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AgentToolCatalogsTest {
    @Test
    fun `composite catalog merges non-empty categories`() {
        val files = StaticAgentToolCatalog(
            mapOf(
                ToolCategory.FILES to mapOf("ReadFile" to fakeTool("ReadFile")),
                ToolCategory.DESKTOP to emptyMap(),
            )
        )
        val shell = StaticAgentToolCatalog(
            mapOf(ToolCategory.SHELL to mapOf("RunShellCommand" to fakeTool("RunShellCommand")))
        )

        val catalog = CompositeAgentToolCatalog(files, shell)

        assertContains(catalog.toolsByCategory.getValue(ToolCategory.FILES), "ReadFile")
        assertContains(catalog.toolsByCategory.getValue(ToolCategory.SHELL), "RunShellCommand")
        assertFalse(ToolCategory.DESKTOP in catalog.toolsByCategory)
    }

    @Test
    fun `composite catalog rejects duplicate tool names across categories`() {
        val first = StaticAgentToolCatalog(
            mapOf(ToolCategory.FILES to mapOf("SharedName" to fakeTool("SharedName")))
        )
        val second = StaticAgentToolCatalog(
            mapOf(ToolCategory.SHELL to mapOf("SharedName" to fakeTool("SharedName")))
        )

        val error = assertFailsWith<IllegalArgumentException> {
            CompositeAgentToolCatalog(first, second).toolsByCategory
        }

        assertContains(error.message.orEmpty(), "SharedName")
    }

    @Test
    fun `static catalog filters empty categories`() {
        val catalog = StaticAgentToolCatalog(
            mapOf(
                ToolCategory.FILES to emptyMap(),
                ToolCategory.SHELL to mapOf("RunShellCommand" to fakeTool("RunShellCommand")),
            )
        )

        assertEquals(setOf(ToolCategory.SHELL), catalog.toolsByCategory.keys)
    }

    private fun fakeTool(name: String): LLMToolSetup = object : LLMToolSetup {
        override val fn: LLMRequest.Function = LLMRequest.Function(
            name = name,
            description = "Fake tool",
            parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            LLMRequest.Message(LLMMessageRole.function, "ok", name = functionCall.name)
    }
}
