package ru.souz.llms

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.providerHttpClientDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodexChatAPIRequestTest {
    @Test
    fun `terminal stream failure is not followed by fallback success`() = runTest {
        val fixture = streamingFixture(CODEX_FAILED_STREAM)

        try {
            val responses = fixture.api.messageStream(chatRequest()).toList()

            val error = assertIs<LLMResponse.Chat.Error>(responses.single())
            assertEquals("terminal failure", error.message)
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun `message does not mask terminal stream failure with partial output`() = runTest {
        val fixture = streamingFixture(CODEX_FAILED_STREAM)

        try {
            val response = fixture.api.message(chatRequest())

            val error = assertIs<LLMResponse.Chat.Error>(response)
            assertEquals("terminal failure", error.message)
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun `tool array properties include an item schema`() {
        val request = invokeBuildResponsesRequest(
            api = createApi(),
            body = LLMRequest.Chat(
                model = LLMModel.CodexGpt54.alias,
                maxTokens = 256,
                messages = listOf(
                    LLMRequest.Message(role = LLMMessageRole.user, content = "list skills"),
                ),
                functions = listOf(
                    LLMRequest.Function(
                        name = "GetSkills",
                        description = "Get skills",
                        parameters = LLMRequest.Parameters(
                            type = "object",
                            properties = mapOf(
                                "skillIds" to LLMRequest.Property(type = "array"),
                            ),
                        ),
                    )
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val tools = request["tools"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val parameters = tools.single()["parameters"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val properties = parameters["properties"] as Map<String, Map<String, Any?>>
        assertNotNull(properties.getValue("skillIds")["items"])
    }

    @Test
    fun `function call output is typed without exposing its history payload as text`() {
        val api = createApi()
        val response = invokeBuildChatOkFromItems(
            api = api,
            items = listOf(
                restJsonMapper.readTree(
                    """
                    {
                      "type": "function_call",
                      "call_id": "call_123",
                      "name": "RunSkillCommand",
                      "arguments": "{\"skillId\":\"InternetSearch\",\"arguments\":{\"query\":\"Kotlin coroutines\"}}"
                    }
                    """.trimIndent()
                )
            ),
        )

        val choice = response.choices.single()
        assertTrue(choice.message.content.isEmpty())
        assertEquals("call_123", choice.message.functionsStateId)
        assertEquals(
            LLMResponse.FunctionCall(
                name = "RunSkillCommand",
                arguments = mapOf(
                    "skillId" to "InternetSearch",
                    "arguments" to mapOf("query" to "Kotlin coroutines"),
                ),
            ),
            choice.message.functionCall,
        )

        val historyMessage = assertNotNull(choice.toMessage())
        assertEquals("call_123", historyMessage.functionsStateId)
        assertTrue(historyMessage.content.isEmpty())
        assertEquals("RunSkillCommand", historyMessage.functionCall?.name)
        assertEquals(
            restJsonMapper.readTree(
                """{"skillId":"InternetSearch","arguments":{"query":"Kotlin coroutines"}}"""
            ),
            restJsonMapper.readTree(historyMessage.functionCall?.arguments),
        )

        val inputItem = invokeMapMessageToInputItem(api, historyMessage)
        assertEquals("function_call", inputItem["type"])
        assertEquals("call_123", inputItem["call_id"])
        assertEquals("RunSkillCommand", inputItem["name"])
        assertEquals(
            restJsonMapper.readTree(
                """{"skillId":"InternetSearch","arguments":{"query":"Kotlin coroutines"}}"""
            ),
            restJsonMapper.readTree(inputItem["arguments"] as String),
        )
    }

    private fun createApi(): CodexChatAPI {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        return CodexChatAPI(
            settingsProvider = settingsProvider,
            oauthService = mockk<CodexOAuthService>(relaxed = true),
            client = mockk<HttpClient>(),
        )
    }

    private fun streamingFixture(streamBody: String): StreamingFixture {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.codexAccountId } returns "account-id"
        val oauthService = mockk<CodexOAuthService>()
        coEvery { oauthService.refreshTokenIfNeeded() } returns "access-token"
        val client = HttpClient(
            MockEngine {
                respond(
                    content = streamBody,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                )
            }
        ) {
            providerHttpClientDefaults()
        }
        return StreamingFixture(
            api = CodexChatAPI(settingsProvider, oauthService, client),
            client = client,
        )
    }

    private fun chatRequest() = LLMRequest.Chat(
        model = LLMModel.CodexGpt54.alias,
        messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
    )

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildResponsesRequest(
        api: CodexChatAPI,
        body: LLMRequest.Chat,
    ): Map<String, Any?> {
        val method = CodexChatAPI::class.java.getDeclaredMethod(
            "buildResponsesRequest",
            LLMRequest.Chat::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(api, body, false) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildChatOkFromItems(
        api: CodexChatAPI,
        items: List<JsonNode>,
    ): LLMResponse.Chat.Ok {
        val method = CodexChatAPI::class.java.getDeclaredMethod(
            "buildChatOkFromItems",
            List::class.java,
            JsonNode::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(api, items, null, LLMModel.CodexGpt54.alias, 123L) as LLMResponse.Chat.Ok
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeMapMessageToInputItem(
        api: CodexChatAPI,
        message: LLMRequest.Message,
    ): Map<String, Any?> {
        val method = CodexChatAPI::class.java.getDeclaredMethod(
            "mapMessageToInputItem",
            LLMRequest.Message::class.java,
        )
        method.isAccessible = true
        return method.invoke(api, message) as Map<String, Any?>
    }

    private data class StreamingFixture(
        val api: CodexChatAPI,
        val client: HttpClient,
    )

    private companion object {
        val CODEX_FAILED_STREAM =
            """
            event: response.output_item.done
            data: {"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"partial"}]}}

            event: response.failed
            data: {"type":"response.failed","response":{"error":"terminal failure"}}

            data: [DONE]

            """.trimIndent()
    }
}
