package ru.souz.llms

import io.mockk.every
import io.mockk.mockk
import ru.souz.db.SettingsProvider
import ru.souz.llms.qwen.QwenChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals

class QwenChatAPIRequestTest {
    @Test
    fun `buildChatRequest can force a specific tool choice`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = LLMRequest.Chat(
                model = "qwen-flash",
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(role = LLMMessageRole.user, content = "Extract memory candidates"),
                ),
                functionCall = "propose_memory_candidates",
                functions = listOf(function("propose_memory_candidates")),
            ),
        )

        assertEquals(
            mapOf(
                "type" to "function",
                "function" to mapOf("name" to "propose_memory_candidates"),
            ),
            request["tool_choice"],
        )
    }

    private fun createApi(): QwenChatAPI {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.qwenChatKey } returns "test-key"
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.gigaModel } returns LLMModel.QwenFlash

        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        return QwenChatAPI(settingsProvider, tokenLogging)
    }

    private fun function(name: String): LLMRequest.Function = LLMRequest.Function(
        name = name,
        description = "$name description",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "query" to LLMRequest.Property(type = "string", description = "Query"),
            ),
            required = listOf("query"),
        ),
    )

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
}
