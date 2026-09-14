package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChatRoutingMetadataTest {
    @Test
    fun `provider route survives copies but cannot cross the JSON boundary`() {
        val request = LLMRequest.Chat(model = "Deployment/v2", provider = LlmProvider.ANTHROPIC, messages = emptyList())
        assertEquals(request.provider, request.copy(stream = true).provider)
        val json = restJsonMapper.writeValueAsString(request)
        assertFalse(restJsonMapper.readTree(json).has("provider"))
        val decoded = restJsonMapper.readValue<LLMRequest.Chat>("""{"model":"Deployment/v2","provider":"OPENAI","messages":[]}""")
        assertNull(decoded.provider)
        assertEquals(request.model, decoded.model)
    }
}
