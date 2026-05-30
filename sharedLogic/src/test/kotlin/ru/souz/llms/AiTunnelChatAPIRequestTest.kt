package ru.souz.llms

import io.mockk.every
import io.mockk.mockk
import ru.souz.db.SettingsProvider
import ru.souz.llms.tunnel.AiTunnelChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AiTunnelChatAPIRequestTest {

    @Test
    fun `buildChatRequest includes items schema for array properties`() {
        val api = createApi()
        val request = invokeBuildChatRequest(
            api = api,
            body = LLMRequest.Chat(
                model = LLMModel.AiTunnelGpt5Nano.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(role = LLMMessageRole.user, content = "run tool"),
                ),
                functions = listOf(
                    LLMRequest.Function(
                        name = "PresentationCreate",
                        description = "Create slides",
                        parameters = LLMRequest.Parameters(
                            type = "object",
                            properties = mapOf(
                                "slides" to LLMRequest.Property(
                                    type = "array",
                                    description = "Array of slide objects",
                                ),
                            ),
                            required = listOf("slides"),
                        ),
                    )
                ),
            ),
            stream = false,
        )

        @Suppress("UNCHECKED_CAST")
        val tools = request["tools"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val function = tools.first()["function"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val parameters = function["parameters"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val properties = parameters["properties"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val slides = properties["slides"] as Map<String, Any?>

        assertEquals("array", slides["type"])
        assertNotNull(slides["items"])
    }

    private fun createApi(): AiTunnelChatAPI {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.aiTunnelKey } returns "test-key"
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.gigaModel } returns LLMModel.AiTunnelGpt5Nano

        val tokenLogging = mockk<TokenLogging>(relaxed = true)
        return AiTunnelChatAPI(settingsProvider, tokenLogging)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildChatRequest(
        api: AiTunnelChatAPI,
        body: LLMRequest.Chat,
        stream: Boolean,
    ): Map<String, Any> {
        val method = AiTunnelChatAPI::class.java.getDeclaredMethod(
            "buildChatRequest",
            LLMRequest.Chat::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(api, body, stream) as Map<String, Any>
    }
}
