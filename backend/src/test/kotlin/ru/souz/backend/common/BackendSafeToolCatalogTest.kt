package ru.souz.backend.common

import kotlin.test.Test
import kotlin.test.assertTrue
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

class BackendSafeToolCatalogTest {
    private fun dummySetup(name: String): LLMToolSetup = object : LLMToolSetup {
        override val fn: LLMRequest.Function = LLMRequest.Function(
            name = name,
            description = "$name description",
            parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
            returnParameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            LLMRequest.Message(LLMMessageRole.function, "ok")
    }

    @Test
    fun `channel messaging is a backend-safe category`() {
        assertTrue(ToolCategory.CHANNEL_MESSAGING in BACKEND_SAFE_TOOL_CATEGORIES)
    }

    @Test
    fun `backendSafeToolNames includes channel messaging tools`() {
        val catalog = object : AgentToolCatalog {
            override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> = mapOf(
                ToolCategory.CHANNEL_MESSAGING to mapOf(
                    "ListActiveChannels" to dummySetup("ListActiveChannels"),
                    "SendMessageToChannel" to dummySetup("SendMessageToChannel"),
                ),
            )
        }

        val names = backendSafeToolNames(catalog)

        assertTrue("ListActiveChannels" in names)
        assertTrue("SendMessageToChannel" in names)
    }
}
