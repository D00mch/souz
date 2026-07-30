package ru.souz.llms.giga

import com.fasterxml.jackson.module.kotlin.readValue
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper
import ru.souz.llms.toMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GigaChatApiContractTest {

    @Test
    fun `chat request uses documented giga field names`() {
        val json = restJsonMapper.writeValueAsString(
            LLMRequest.Chat(
                model = "GigaChat-2-Max",
                messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
                functionCall = mapOf("name" to "weather_forecast"),
                temperature = 0.2f,
                topP = 0.8f,
                maxTokens = 42,
                repetitionPenalty = 1.1f,
                reasoningEffort = "medium",
                responseFormat = mapOf(
                    "type" to "json_schema",
                    "schema" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("answer" to mapOf("type" to "string")),
                        "required" to listOf("answer"),
                    ),
                    "strict" to true,
                ),
            )
        )
        val node = restJsonMapper.readTree(json)

        assertEquals("GigaChat-2-Max", node["model"].asText())
        assertEquals(42, node["max_tokens"].asInt())
        assertFalse(node.has("maxTokens"))
        assertEquals(0.8, node["top_p"].asDouble())
        assertEquals(1.1, node["repetition_penalty"].asDouble())
        assertEquals("medium", node["reasoning_effort"].asText())
        assertEquals("weather_forecast", node["function_call"]["name"].asText())
        assertEquals("json_schema", node["response_format"]["type"].asText())
    }

    @Test
    fun `chat request normalizes nested object schemas for giga`() {
        val request = LLMRequest.Chat(
            model = "GigaChat-2-Max",
            messages = listOf(LLMRequest.Message(LLMMessageRole.user, "hello")),
            functions = listOf(
                LLMRequest.Function(
                    name = "lookup",
                    parameters = LLMRequest.Parameters(
                        type = "object",
                        properties = mapOf(
                            "payload" to LLMRequest.Property("object", "Input payload")
                        ),
                    ),
                    returnParameters = LLMRequest.Parameters(
                        type = "object",
                        properties = mapOf(
                            "error" to LLMRequest.Property("object", "Structured error")
                        ),
                    ),
                )
            ),
        ).toGigaChatRequest()

        assertEquals(
            emptyMap(),
            request.functions.single().parameters.properties.getValue("payload").properties,
        )
        assertEquals(
            emptyMap(),
            request.functions.single().returnParameters!!.properties.getValue("error").properties,
        )
    }

    @Test
    fun `assistant tool call history keeps function call outside content`() {
        val message = LLMResponse.Choice(
            message = LLMResponse.Message(
                content = "",
                role = LLMMessageRole.assistant,
                functionCall = LLMResponse.FunctionCall("weather_forecast", mapOf("city" to "Moscow")),
                functionsStateId = "state-1",
            ),
            index = 0,
            finishReason = LLMResponse.FinishReason.function_call,
        ).toMessage()

        assertNotNull(message)
        assertEquals("", message.content)
        assertEquals("state-1", message.functionsStateId)
        assertEquals("weather_forecast", message.functionCall?.name)
        assertEquals("""{"city":"Moscow"}""", message.functionCall?.arguments)
    }

    @Test
    fun `function call arguments parse from object or json string`() {
        val objectArguments: LLMResponse.FunctionCall = restJsonMapper.readValue(
            """{"name":"weather_forecast","arguments":{"city":"Moscow"}}"""
        )
        val stringArguments: LLMResponse.FunctionCall = restJsonMapper.readValue(
            """{"name":"weather_forecast","arguments":"{\"city\":\"Moscow\"}"}"""
        )

        assertEquals(mapOf("city" to "Moscow"), objectArguments.arguments)
        assertEquals(mapOf("city" to "Moscow"), stringArguments.arguments)
    }

    @Test
    fun `stream final chunk preserves finish reason and usage`() {
        val chunk = parseGigaStreamChunk(
            """
            {
              "choices": [
                {
                  "delta": { "content": "" },
                  "index": 0,
                  "finish_reason": "stop"
                }
              ],
              "created": 1754637655,
              "model": "GigaChat:2.0.28.2",
              "object": "chat.completion",
              "usage": {
                "prompt_tokens": 56,
                "completion_tokens": 31,
                "total_tokens": 87,
                "precached_prompt_tokens": 3
              }
            }
            """.trimIndent()
        ) as LLMResponse.Chat.Ok

        assertEquals(LLMResponse.FinishReason.stop, chunk.choices.single().finishReason)
        assertEquals(87, chunk.usage.totalTokens)
        assertEquals(3, chunk.usage.precachedTokens)
    }

    @Test
    fun `stream parser supports function in progress role`() {
        val chunk = parseGigaStreamChunk(
            """
            {
              "choices": [
                {
                  "delta": {
                    "role": "function_in_progress",
                    "content": "10 seconds left",
                    "created": 1625284800,
                    "name": "text2image"
                  },
                  "index": 0
                }
              ],
              "created": 1754637655,
              "model": "GigaChat:2.0.28.2",
              "object": "chat.completion"
            }
            """.trimIndent()
        ) as LLMResponse.Chat.Ok

        val message = chunk.choices.single().message
        assertEquals(LLMMessageRole.function_in_progress, message.role)
        assertEquals("text2image", message.name)
        assertEquals(1625284800, message.created)
        assertTrue(message.content.isNotBlank())
    }
}
