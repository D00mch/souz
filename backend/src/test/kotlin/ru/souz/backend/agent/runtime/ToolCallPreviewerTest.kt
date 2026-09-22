package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.souz.llms.restJsonMapper

class ToolCallPreviewerTest {
    private val previewer = ToolCallPreviewer()

    @Test
    fun `json and node describe the same preview`() {
        val preview = previewer.argumentsPreview(mapOf("token" to "t", "items" to listOf(1, 2)))

        assertEquals("""{"token":"[REDACTED]","items":[1,2]}""", preview.json)
        assertEquals(preview.json, restJsonMapper.writeValueAsString(preview.node))
    }

    @Test
    fun `empty collections stay empty`() {
        assertEquals("{}", previewer.argumentsPreview(emptyMap<String, Any?>()).json)
        assertEquals("[]", previewer.resultPreview(emptyList<Any?>()).json)
        assertEquals("[]", previewer.resultPreview("[]").json)
        assertEquals("null", previewer.resultPreview(null).json)
    }

    @Test
    fun `collections at the limit are kept whole and above it are truncated`() {
        assertEquals(
            """{"k1":1,"k2":2,"k3":3,"k4":4,"k5":5,"k6":6,"k7":7,"k8":8}""",
            previewer.argumentsPreview((1..8).associate { "k$it" to it }).json,
        )
        assertEquals(
            """{"k1":1,"k2":2,"k3":3,"k4":4,"k5":5,"k6":6,"k7":7,"k8":8,"_truncated":"12 more fields"}""",
            previewer.argumentsPreview((1..20).associate { "k$it" to it }).json,
        )
        assertEquals("[1,2,3,4,5,6,7,8]", previewer.resultPreview((1..8).toList()).json)
        assertEquals(
            """[1,2,3,4,5,6,7,8,"[TRUNCATED 12 more items]"]""",
            previewer.resultPreview((1..20).toList()).json,
        )
    }

    @Test
    fun `deep nesting is cut at the depth limit`() {
        assertEquals(
            """{"child":{"child":{"child":{"child":{"child":"leaf"}}}}}""",
            previewer.argumentsPreview(nested(5)).json,
        )
        assertEquals(
            """{"child":{"child":{"child":{"child":{"child":{"child":"[TRUNCATED]"}}}}}}""",
            previewer.argumentsPreview(nested(10)).json,
        )
        assertEquals("""[[[[[["[TRUNCATED]"]]]]]]""", previewer.resultPreview(nestedArrays(10)).json)
    }

    @Test
    fun `sensitive keys and text are redacted at every level`() {
        val preview = previewer.argumentsPreview(
            mapOf(
                "api_key" to "abc",
                "Authorization" to "Bearer abc",
                "nested" to mapOf("password" to "p", "ok" to "fine"),
                "list" to listOf(mapOf("secret" to "s")),
                "text" to "key sk-abc123 and Bearer tok.en my_token=xyz",
            ),
        )

        assertEquals(
            """{"api_key":"[REDACTED]","Authorization":"[REDACTED]","nested":{"password":"[REDACTED]","ok":"fine"},""" +
                """"list":[{"secret":"[REDACTED]"}],"text":"key [REDACTED] and Bearer [REDACTED] [REDACTED]=xyz"}""",
            preview.json,
        )
        assertEquals("""{"a":1,"password":"[REDACTED]"}""", previewer.resultPreview("""{"a":1,"password":"x"}""").json)
        assertEquals(""""just text with [REDACTED]=abc"""", previewer.resultPreview("just text with token=abc").json)
    }

    @Test
    fun `oversized previews collapse into one truncated text`() {
        val longText = "v".repeat(200)
        val preview = previewer.resultPreview((1..8).associate { "field$it" to longText })

        assertEquals(
            """{"text":"${"v".repeat(157)}..."}""",
            previewer.argumentsPreview(mapOf("text" to longText)).json,
        )
        assertTrue(preview.node.isTextual)
        assertEquals(1_024, preview.node.asText().length)
        assertTrue(preview.node.asText().startsWith("""{"field1":"vvv"""))
        assertTrue(preview.node.asText().endsWith("..."))
        assertEquals(restJsonMapper.writeValueAsString(preview.node), preview.json)
    }

    @Test
    fun `unserializable values fall back to placeholders`() {
        assertEquals(""""[UNAVAILABLE_ARGUMENTS]"""", previewer.argumentsPreview(Broken()).json)
        assertEquals(""""[UNAVAILABLE_RESULT]"""", previewer.resultPreview(Broken()).json)
        assertEquals("IllegalStateException", previewer.safeErrorPreview(IllegalStateException("  ")))
        assertEquals(
            "IllegalStateException: [REDACTED]=abc Bearer [REDACTED]",
            previewer.safeErrorPreview(IllegalStateException("token=abc Bearer xyz")),
        )
        assertEquals(240, previewer.safeErrorPreview(RuntimeException("x".repeat(500))).length)
    }

    @Test
    fun `input json tree is not modified and not shared with the preview`() {
        val input = JsonNodeFactory.instance.objectNode()
        input.put("token", "t")
        input.putArray("items").add(1).add("two")
        input.putObject("inner").put("deep", "x")
        val before: JsonNode = input.deepCopy()

        val preview = previewer.argumentsPreview(input)
        (preview.node.get("inner") as ObjectNode).put("added", true)
        (preview.node.get("items") as ArrayNode).add(3)

        assertEquals(before, input)
        assertEquals("""{"token":"[REDACTED]","items":[1,"two"],"inner":{"deep":"x"}}""", preview.json)
    }

    private fun nested(depth: Int): Any = if (depth == 0) "leaf" else mapOf("child" to nested(depth - 1))

    private fun nestedArrays(depth: Int): Any = if (depth == 0) "leaf" else listOf(nestedArrays(depth - 1))

    private class Broken {
        @Suppress("unused")
        val value: String get() = error("boom")
    }
}
