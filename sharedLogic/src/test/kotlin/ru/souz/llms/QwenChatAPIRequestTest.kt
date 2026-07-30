package ru.souz.llms

import io.mockk.every
import io.mockk.mockk
import ru.souz.db.SettingsProvider
import ru.souz.llms.qwen.QwenChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QwenChatAPIRequestTest {

    @Test
    fun `buildChatRequest replays assistant tool call before tool result`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = LLMRequest.Chat(
                model = LLMModel.QwenFlash.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.assistant,
                        content = "",
                        functionsStateId = "call_123",
                        functionCall = LLMRequest.FunctionCall(
                            name = "lookup",
                            arguments = """{"query":"report"}""",
                        ),
                    ),
                    LLMRequest.Message(
                        role = LLMMessageRole.function,
                        content = """{"result":"ok"}""",
                        functionsStateId = "call_123",
                        name = "lookup",
                    ),
                ),
                functions = listOf(function("lookup")),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val messages = request["messages"] as List<Map<String, Any?>>
        assertEquals("assistant", messages[0]["role"])
        assertNull(messages[0]["content"])
        @Suppress("UNCHECKED_CAST")
        val toolCalls = messages[0]["tool_calls"] as List<Map<String, Any?>>
        assertEquals("call_123", toolCalls.single()["id"])
        @Suppress("UNCHECKED_CAST")
        val toolFunction = toolCalls.single()["function"] as Map<String, Any?>
        assertEquals("lookup", toolFunction["name"])
        assertEquals("""{"query":"report"}""", toolFunction["arguments"])

        assertEquals("tool", messages[1]["role"])
        assertEquals("call_123", messages[1]["tool_call_id"])
        assertNotNull(messages[1]["content"])
    }

    private fun createApi(): QwenChatAPI {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.qwenChatKey } returns "test-key"
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.gigaModel } returns LLMModel.QwenFlash

        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        return QwenChatAPI(settingsProvider, tokenLogging)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildChatRequest(
        api: QwenChatAPI,
        body: LLMRequest.Chat,
    ): Map<String, Any> {
        val method = QwenChatAPI::class.java.getDeclaredMethod(
            "buildChatRequest",
            LLMRequest.Chat::class.java,
        )
        method.isAccessible = true
        return method.invoke(api, body) as Map<String, Any>
    }

    private fun function(name: String) = LLMRequest.Function(
        name = name,
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf("query" to LLMRequest.Property("string", "Search query")),
            required = listOf("query"),
        ),
    )
}
