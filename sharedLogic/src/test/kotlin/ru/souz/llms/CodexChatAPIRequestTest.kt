package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.llms.codex.CodexChatAPI
import ru.souz.llms.codex.CodexOAuthService
import ru.souz.llms.http.providerHttpClientDefaults

class CodexChatAPIRequestTest {
    @Test
    fun `reasoning effort is nested for Responses and omitted when unset`() = runTest {
        val configured = captureRequest(chatRequest().copy(reasoningEffort = "low"))
        assertEquals(mapOf("effort" to "low"), configured["reasoning"])
        assertTrue("reasoning_effort" !in configured)
        assertTrue("reasoning" !in captureRequest(chatRequest()))
    }

    @Test
    fun `terminal stream failure is not followed by fallback success`() = runTest {
        HttpClient(responseEngine(CODEX_FAILED_STREAM)) { providerHttpClientDefaults() }.use { client ->
            val responses = createApi(client).messageStream(chatRequest()).toList()

            val error = assertIs<LLMResponse.Chat.Error>(responses.single())
            assertEquals("terminal failure", error.message)
        }
    }

    @Test
    fun `message does not mask terminal stream failure with partial output`() = runTest {
        HttpClient(responseEngine(CODEX_FAILED_STREAM)) { providerHttpClientDefaults() }.use { client ->
            val response = createApi(client).message(chatRequest())

            val error = assertIs<LLMResponse.Chat.Error>(response)
            assertEquals("terminal failure", error.message)
        }
    }

    @Test
    fun `tool array properties include an item schema`() = runTest {
        val request = captureRequest(
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
    fun `function call output is typed without exposing its history payload as text`() = runTest {
        val engine = responseEngine(CODEX_TOOL_STREAM)
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            val api = createApi(client)
            val response = assertIs<LLMResponse.Chat.Ok>(api.message(chatRequest()))

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

            assertIs<LLMResponse.Chat.Ok>(api.message(chatRequest().copy(messages = listOf(historyMessage))))
            val request = restJsonMapper.readValue<Map<String, Any?>>(engine.requestHistory.last().body.toByteArray())
            @Suppress("UNCHECKED_CAST")
            val inputItem = (request["input"] as List<Map<String, Any?>>).single()
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
    }

    private fun createApi(client: HttpClient): CodexChatAPI {
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.requestTimeoutMillis } returns 1_000L
        every { settingsProvider.codexAccountId } returns "account-id"
        val oauthService = mockk<CodexOAuthService>()
        coEvery { oauthService.refreshTokenIfNeeded() } returns "access-token"
        return CodexChatAPI(settingsProvider, oauthService, client)
    }

    private fun responseEngine(streamBody: String) = MockEngine {
        respond(
            content = streamBody,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
        )
    }

    private suspend fun captureRequest(body: LLMRequest.Chat): Map<String, Any?> {
        val engine = responseEngine(CODEX_COMPLETED_STREAM)
        HttpClient(engine) { providerHttpClientDefaults() }.use { client ->
            assertIs<LLMResponse.Chat.Ok>(createApi(client).message(body))
        }
        return restJsonMapper.readValue(engine.requestHistory.single().body.toByteArray())
    }

    private fun chatRequest() = LLMRequest.Chat(
        model = LLMModel.CodexGpt54.alias,
        messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
    )

    private companion object {
        const val CODEX_COMPLETED_STREAM =
            "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{}}\n\ndata: [DONE]\n\n"
        val CODEX_TOOL_STREAM =
            """
            event: response.output_item.done
            data: {"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_123","name":"RunSkillCommand","arguments":"{\"skillId\":\"InternetSearch\",\"arguments\":{\"query\":\"Kotlin coroutines\"}}"}}

            event: response.completed
            data: {"type":"response.completed","response":{}}

            data: [DONE]

            """.trimIndent() + "\n\n"
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
