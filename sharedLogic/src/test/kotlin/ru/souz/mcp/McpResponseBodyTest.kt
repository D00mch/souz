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
    fun `response bytes are bounded even without using the SDK decoder`() {
        val body = "x".repeat(McpLimits.RESPONSE_BYTES.toInt() + 1).toResponseBody("application/json".toMediaType())
        McpResponseBody(body).use { assertFailsWith<IOException> { it.source().readUtf8() } }
    }

    @Test
    fun `JSON and SSE nesting are checked before frame decoding including CR line endings and BOM`() {
        val nested = "[".repeat(McpLimits.JSON_DEPTH + 1) + "0" + "]".repeat(McpLimits.JSON_DEPTH + 1)
        val cases = listOf("application/json" to nested) + listOf("\n", "\r", "\r\n").flatMap { end ->
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
