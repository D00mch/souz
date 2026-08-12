package ru.souz.llms

import io.mockk.every
import io.mockk.mockk
import io.ktor.client.HttpClient
import ru.souz.db.SettingsProvider
import ru.souz.llms.tunnel.AiTunnelChatAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AiTunnelChatAPIRequestTest {

    @Test
    fun `stream request asks AiTunnel to include usage`() {
        val request = invokeBuildChatRequest(
            api = createApi(),
            body = LLMRequest.Chat(
                model = LLMModel.AiTunnelGpt5Nano.alias,
                messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
            ),
            stream = true,
        )

        assertEquals(mapOf("include_usage" to true), request["stream_options"])
    }

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
                        name = "BatchProcess",
                        description = "Process records",
                        parameters = LLMRequest.Parameters(
                            type = "object",
                            properties = mapOf(
                                "records" to LLMRequest.Property(
                                    type = "array",
                                    description = "Array of record objects",
                                ),
                            ),
                            required = listOf("records"),
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
        val records = properties["records"] as Map<String, Any?>

        assertEquals("array", records["type"])
        assertNotNull(records["items"])
    }

    private fun createApi(): AiTunnelChatAPI {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.aiTunnelKey } returns "test-key"
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.gigaModel } returns LLMModel.AiTunnelGpt5Nano

        return AiTunnelChatAPI(settingsProvider, mockk<HttpClient>())
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
