package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.llms.restJsonMapper

class ToolCallPreviewerTest {
    private val previewer = ToolCallPreviewer()

    @Test
    fun `previews preserve JSON shape order redaction and collection limits`() {
        val cases = listOf(
            null to "null",
            emptyMap<String, Any?>() to "{}",
            emptyList<Any?>() to "[]",
            listOf(true, false, null, 1.25, -0.5) to "[true,false,null,1.25,-0.5]",
            (1..8).toList() to "[1,2,3,4,5,6,7,8]",
            (1..20).toList() to """[1,2,3,4,5,6,7,8,"[TRUNCATED 12 more items]"]""",
            (1..8).associate { "k$it" to it } to """{"k1":1,"k2":2,"k3":3,"k4":4,"k5":5,"k6":6,"k7":7,"k8":8}""",
            (1..20).associate { "k$it" to it } to
                """{"k1":1,"k2":2,"k3":3,"k4":4,"k5":5,"k6":6,"k7":7,"k8":8,"_truncated":"12 more fields"}""",
            "[[[[[1]]]]]" to "[[[[[1]]]]]",
            "[".repeat(10) + "1" + "]".repeat(10) to """[[[[[["[TRUNCATED]"]]]]]]""",
            """{"a":{"b":{"c":{"d":{"e":{"f":true}}}}}}""" to
                """{"a":{"b":{"c":{"d":{"e":{"f":"[TRUNCATED]"}}}}}}""",
            """{"api_key":"abc","items":[{"password":"xyz"},"Bearer abc","sk-abc123"]}""" to
                """{"api_key":"[REDACTED]","items":[{"password":"[REDACTED]"},"Bearer [REDACTED]","[REDACTED]"]}""",
            """{"api-key":"abc","Refresh_Token":"xyz","visible":true}""" to
                """{"api-key":"[REDACTED]","Refresh_Token":"[REDACTED]","visible":true}""",
            "plain text" to "\"plain text\"",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, previewer.serializePreview(previewer.argumentsPreview(input)), "arguments: $input")
            assertEquals(expected, previewer.serializePreview(previewer.resultPreview(input)), "result: $input")
        }
    }

    @Test
    fun `long strings and oversized previews retain their truncation markers`() {
        assertEquals("v".repeat(157) + "...", previewer.resultPreview("v".repeat(200)).asText())
        val input = (1..8).associate { "field$it" to "v".repeat(160) }
        val expected = restJsonMapper.writeValueAsString(input).take(1_021) + "..."
        val preview = previewer.resultPreview(input)
        assertEquals(expected, preview.textValue())
        assertEquals(restJsonMapper.writeValueAsString(expected), previewer.serializePreview(preview))
    }

    @Test
    fun `preview containers are independent of the input tree`() {
        val json = """{"token":"abc","items":[{"value":1}],"inner":{"ok":true}}"""
        val input = restJsonMapper.readTree(json) as ObjectNode
        val preview = previewer.argumentsPreview(input) as ObjectNode
        assertEquals("[REDACTED]", preview["token"].asText())
        (preview["items"][0] as ObjectNode).put("value", 2)
        (preview["items"] as ArrayNode).add(3)
        (preview["inner"] as ObjectNode).put("ok", false)
        preview.put("added", true)
        assertEquals(json, restJsonMapper.writeValueAsString(input))
    }

    @Test
    fun `conversion and serialization failures retain safe placeholders`() {
        val mapper = mockk<ObjectMapper>()
        every { mapper.valueToTree<JsonNode>(any()) } throws IllegalStateException("sensitive input")
        every { mapper.writeValueAsString(any<JsonNode>()) } throws IllegalStateException("serialization failed")
        every { mapper.writeValueAsString("[UNAVAILABLE_PREVIEW]") } returns "\"[UNAVAILABLE_PREVIEW]\""
        val failingPreviewer = ToolCallPreviewer(mapper)
        val arguments = failingPreviewer.argumentsPreview(Any())
        assertEquals("[UNAVAILABLE_ARGUMENTS]", arguments.asText())
        assertEquals("[UNAVAILABLE_RESULT]", failingPreviewer.resultPreview(Any()).asText())
        assertEquals("\"[UNAVAILABLE_PREVIEW]\"", failingPreviewer.serializePreview(arguments))
    }
}
