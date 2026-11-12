package ru.souz.mcp

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpResponseBodyTest {
    @Test
    fun `SSE data line trimming preserves split escape depth checks`() {
        for (end in listOf("\n", "\r", "\r\n")) {
            for (padding in listOf(" ", "\t", "\u2003")) {
                for (depth in listOf(McpLimits.JSON_DEPTH - 1, McpLimits.JSON_DEPTH)) {
                    val lines = listOf("{\"escaped\":\"\\${padding}", "${padding}\"\",\"nested\":" +
                        "[".repeat(depth) + "0" + "]".repeat(depth) + "}")
                    // The SDK trims and concatenates each data line before JSON decoding.
                    val decoded = restJsonMapper.readTree(lines.joinToString("") { it.trim() })
                    assertEquals("\"", decoded.path("escaped").asText())
                    val sse = lines.joinToString(end, postfix = end + end) { "data:$it" }
                    McpResponseBody(sse.toResponseBody("text/event-stream".toMediaType())).use {
                        if (depth == McpLimits.JSON_DEPTH) {
                            assertFailsWith<IOException> { it.source().readUtf8() }
                        } else {
                            assertEquals(sse, it.source().readUtf8())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `response bytes are bounded even without using the SDK decoder`() {
        val body = "x".repeat(McpLimits.RESPONSE_BYTES.toInt() + 1).toResponseBody("application/json".toMediaType())
        McpResponseBody(body).use { assertFailsWith<IOException> { it.source().readUtf8() } }
    }

    @Test
    fun `JSON and SSE nesting are checked before frame decoding including CR line endings and BOM`() {
        val nested = "[".repeat(McpLimits.JSON_DEPTH + 1) + "0" + "]".repeat(McpLimits.JSON_DEPTH + 1)
        val cases = listOf("application/json" to nested, "text/event-stream" to "data: $nested") + listOf("\n", "\r", "\r\n").flatMap { end ->
            listOf("", "\uFEFF").map { bom ->
                "text/event-stream" to "${bom}id: \"$end$end" + "data: $nested$end$end"
            }
        }
        cases.forEach { (type, value) ->
            McpResponseBody(value.toResponseBody(type.toMediaType())).use {
                assertFailsWith<IOException> { it.source().readUtf8() }
            }
        }
        val quoted = restJsonMapper.writeValueAsString(mapOf("brackets" to "[".repeat(1_000)))
        McpResponseBody(quoted.toResponseBody("application/json".toMediaType())).use {
            assertEquals(quoted, it.source().readUtf8())
        }
    }
}
