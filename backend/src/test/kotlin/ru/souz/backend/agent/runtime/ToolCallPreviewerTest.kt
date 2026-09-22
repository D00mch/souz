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
        val cases = listOf(
            null to "null",
            "{}" to "{}",
            "[]" to "[]",
            emptyMap<String, Any?>() to "{}",
            emptyList<Any?>() to "[]",
            "[$eight]" to "[$eight]",
            "[$eight,9,10]" to "[$eight,\"[TRUNCATED 2 more items]\"]",
            "{$fields}" to "{$fields}",
            "{$fields,\"f9\":9}" to "{$fields,\"_truncated\":\"1 more fields\"}",
            "[".repeat(5) + "1" + "]".repeat(5) to "[".repeat(5) + "1" + "]".repeat(5),
            "{\"a\":".repeat(5) + "1" + "}".repeat(5) to "{\"a\":".repeat(5) + "1" + "}".repeat(5),
            "[".repeat(6) + "1" + "]".repeat(6) to "[".repeat(6) + "\"[TRUNCATED]\"" + "]".repeat(6),
            "{\"a\":".repeat(6) + "1" + "}".repeat(6) to "{\"a\":".repeat(6) + "\"[TRUNCATED]\"" + "}".repeat(6),
            "[".repeat(7) + "]".repeat(7) to "[".repeat(6) + "\"[TRUNCATED]\"" + "]".repeat(6),
            "{\"a\":".repeat(7) + "1" + "}".repeat(7) to "{\"a\":".repeat(6) + "\"[TRUNCATED]\"" + "}".repeat(6),
            "[true,false,null,1.5]" to "[true,false,null,1.5]",
            listOf(true, false, null, 1.5, -0.5) to "[true,false,null,1.5,-0.5]",
            (1..8).toList() to "[$eight]",
            (1..10).toList() to "[$eight,\"[TRUNCATED 2 more items]\"]",
            (1..8).associate { "f$it" to it } to "{$fields}",
            (1..9).associate { "f$it" to it } to "{$fields,\"_truncated\":\"1 more fields\"}",
            mapOf("items" to listOf(mapOf("password" to "p")), "note" to "Bearer abc.def sk-123") to
                """{"items":[{"password":"[REDACTED]"}],"note":"Bearer [REDACTED] [REDACTED]"}""",
            "x".repeat(160) to "\"" + "x".repeat(160) + "\"",
            "x".repeat(200) to "\"" + "x".repeat(157) + "...\"",
            """{"api-key":"k","Refresh_Token":{"a":1},"items":[{"password":"p"}],"note":"Bearer abc.def sk-123"}""" to
                """{"api-key":"[REDACTED]","Refresh_Token":"[REDACTED]","items":[{"password":"[REDACTED]"}],"note":"Bearer [REDACTED] [REDACTED]"}""",
            """{"api_key":"k","items":["Bearer abc","sk-abc123"]}""" to
                """{"api_key":"[REDACTED]","items":["Bearer [REDACTED]","[REDACTED]"]}""",
            "not json" to "\"not json\"",
        )
        cases.forEach { (input, expected) -> assertStoredPreviews(input, expected) }
    }

    @Test
    fun `oversized preview is stored as a truncated JSON string`() {
        val inputs = listOf(List(8) { "x".repeat(160) }, (1..8).associate { "f$it" to "x".repeat(160) })
        inputs.forEach { input ->
            val truncated = restJsonMapper.writeValueAsString(input).take(1_021) + "..."
            assertStoredPreviews(input, restJsonMapper.writeValueAsString(truncated))
        }
    }

    @Test
    fun `failed conversion or serialization falls back to placeholders`() {
        assertEquals("\"[UNAVAILABLE_ARGUMENTS]\"", previewer.serializePreview(previewer.argumentsPreview(Any())))
        assertEquals("\"[UNAVAILABLE_RESULT]\"", previewer.serializePreview(previewer.resultPreview(Any())))
        val brokenMapper = mockk<ObjectMapper> {
            every { valueToTree<JsonNode>(any()) } throws IllegalStateException("conversion failed")
            every { writeValueAsString(any<JsonNode>()) } throws IllegalStateException("broken")
            every { writeValueAsString("[UNAVAILABLE_PREVIEW]") } returns "\"[UNAVAILABLE_PREVIEW]\""
        }
        val broken = ToolCallPreviewer(brokenMapper)
        assertEquals("[UNAVAILABLE_ARGUMENTS]", broken.argumentsPreview(mapOf("n" to 1)).asText())
        assertEquals("[UNAVAILABLE_RESULT]", broken.resultPreview(listOf(1)).asText())
        listOf(broken::argumentsPreview, broken::resultPreview).forEach { createPreview ->
            val preview = createPreview(null)
            assertTrue(preview.isNull)
            assertEquals("\"[UNAVAILABLE_PREVIEW]\"", broken.serializePreview(preview))
        }
    }

    @Test
    fun `input tree is neither changed nor shared with the preview`() {
        val input = restJsonMapper.readTree("""{"nested":{"n":1},"items":[{"n":1}],"token":"t"}""")
        val snapshot = input.deepCopy<JsonNode>()
        listOf(previewer::argumentsPreview, previewer::resultPreview).forEach { createPreview ->
            val preview = createPreview(input) as ObjectNode
            assertEquals("[REDACTED]", preview["token"].asText())
            (preview["nested"] as ObjectNode).put("n", 2)
            ((preview["items"] as ArrayNode)[0] as ObjectNode).put("n", 2)
            (preview["items"] as ArrayNode).add(3)
            preview.put("added", true)
            assertEquals(snapshot, input, createPreview.name)
        }
    }

    private fun assertStoredPreviews(input: Any?, expected: String) {
        listOf(previewer::argumentsPreview, previewer::resultPreview).forEach { createPreview ->
            assertEquals(expected, previewer.serializePreview(createPreview(input)), "${createPreview.name}: $input")
        }
    }
}
