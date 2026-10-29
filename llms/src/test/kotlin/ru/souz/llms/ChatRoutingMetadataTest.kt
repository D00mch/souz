package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChatRoutingMetadataTest {
    @Test
    fun `retry exhaustion survives copies and exceptions but stays outside the JSON contract`() {
        val error = LLMResponse.Chat.Error(503, "unavailable", retriesExhausted = true)
        assertEquals(true, error.copy().retriesExhausted)
        assertEquals(true, LLMException(error).retriesExhausted)
        assertEquals("""{"status":503,"message":"unavailable"}""", restJsonMapper.writeValueAsString(error))
        val decoded = restJsonMapper.readValue<LLMResponse.Chat.Error>(
            """{"status":503,"message":"unavailable","retriesExhausted":true}""",
        )
        assertFalse(decoded.retriesExhausted)
    }

    @Test
    fun `provider route and local context survive copies but cannot cross the JSON boundary`() {
        val request = LLMRequest.Chat(model = "Deployment/v2", provider = LlmProvider.ANTHROPIC, messages = emptyList(), contextSize = 125_000, maxTokens = 512)
        assertEquals(request.provider, request.copy(stream = true).provider)
        assertEquals(125_000, request.copy(stream = true).contextSize)
        val json = restJsonMapper.writeValueAsString(request)
        assertFalse(restJsonMapper.readTree(json).has("provider"))
        assertFalse(restJsonMapper.readTree(json).has("contextSize"))
        assertEquals(512, restJsonMapper.readTree(json)["max_tokens"].asInt())
        val decoded = restJsonMapper.readValue<LLMRequest.Chat>("""{"model":"Deployment/v2","provider":"OPENAI","contextSize":125000,"messages":[]}""")
        assertNull(decoded.provider)
        assertNull(decoded.contextSize)
        assertEquals(request.model, decoded.model)
    }
}
