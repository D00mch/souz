package ru.souz.llms

import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChatRoutingMetadataTest {
    @Test
    fun `file references round trip in message snapshots and old text messages remain readable`() {
        val old = restJsonMapper.readValue<LLMRequest.Message>("""{"role":"user","content":"hello"}""")
        assertEquals(emptyList(), old.files)
        assertFalse(restJsonMapper.readTree(restJsonMapper.writeValueAsString(old)).has("files"))
        val file = FileContent(FileReference("file-id"), "report.md", "text/markdown", 12)
        val message = old.copy(files = listOf(file), replyFiles = listOf(file), images = listOf(ImageContent(file.file)))
        assertEquals(message, restJsonMapper.readValue<LLMRequest.Message>(restJsonMapper.writeValueAsString(message)))
    }

    @Test
    fun `provider route and agent context budget survive copies but cannot cross the JSON boundary`() {
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
