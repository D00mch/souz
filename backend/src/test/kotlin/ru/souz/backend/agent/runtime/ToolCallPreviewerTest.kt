package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.llms.restJsonMapper

class ToolCallPreviewerTest {
    private val previewer = ToolCallPreviewer()

    @Test
    fun `stored previews keep exact JSON at collection depth string and redaction limits`() {
        val eight = (1..8).joinToString(",")
        val fields = (1..8).joinToString(",") { "\"f$it\":$it" }
        val cases = mapOf(
            "{}" to "{}",
            "[]" to "[]",
            "[$eight]" to "[$eight]",
            "[$eight,9,10]" to "[$eight,\"[TRUNCATED 2 more items]\"]",
            "{$fields}" to "{$fields}",
            "{$fields,\"f9\":9}" to "{$fields,\"_truncated\":\"1 more fields\"}",
            "[".repeat(7) + "]".repeat(7) to "[".repeat(6) + "\"[TRUNCATED]\"" + "]".repeat(6),
            "{\"a\":".repeat(7) + "1" + "}".repeat(7) to "{\"a\":".repeat(6) + "\"[TRUNCATED]\"" + "}".repeat(6),
            "[true,false,null,1.5]" to "[true,false,null,1.5]",
            "x".repeat(200) to "\"" + "x".repeat(157) + "...\"",
            """{"api-key":"k","Refresh_Token":{"a":1},"items":[{"password":"p"}],"note":"Bearer abc.def sk-123"}""" to
                """{"api-key":"[REDACTED]","Refresh_Token":"[REDACTED]","items":[{"password":"[REDACTED]"}],"note":"Bearer [REDACTED] [REDACTED]"}""",
            "not json" to "\"not json\"",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, storedArguments(input), input) }
        assertEquals("null", storedArguments(null))
    }

    @Test
    fun `oversized preview is stored as a truncated JSON string`() {
        val input = List(8) { "x".repeat(160) }
        val truncated = restJsonMapper.writeValueAsString(input).take(1_021) + "..."
        assertEquals(restJsonMapper.writeValueAsString(truncated), storedArguments(input))
    }

    @Test
    fun `failed conversion or serialization falls back to placeholders`() {
        assertEquals("\"[UNAVAILABLE_ARGUMENTS]\"", storedArguments(Any()))
        assertEquals("\"[UNAVAILABLE_RESULT]\"", previewer.serializePreview(previewer.resultPreview(Any())))
        val brokenMapper = mockk<ObjectMapper> {
            every { writeValueAsString(any<JsonNode>()) } throws IllegalStateException("broken")
            every { writeValueAsString("[UNAVAILABLE_PREVIEW]") } returns "\"[UNAVAILABLE_PREVIEW]\""
        }
        val broken = ToolCallPreviewer(brokenMapper)
        val preview = broken.argumentsPreview(null)
        assertTrue(preview.isNull)
        assertEquals("\"[UNAVAILABLE_PREVIEW]\"", broken.serializePreview(preview))
    }

    @Test
    fun `input tree is neither changed nor shared with the preview`() {
        val input = restJsonMapper.readTree("""{"items":[{"n":1}],"token":"t"}""")
        val snapshot = input.deepCopy<JsonNode>()
        val preview = previewer.argumentsPreview(input) as ObjectNode
        ((preview["items"] as ArrayNode)[0] as ObjectNode).put("n", 2)
        (preview["items"] as ArrayNode).add(3)
        preview.put("added", true)
        assertEquals(snapshot, input)
    }

    private fun storedArguments(input: Any?): String =
        previewer.serializePreview(previewer.argumentsPreview(input))
}
