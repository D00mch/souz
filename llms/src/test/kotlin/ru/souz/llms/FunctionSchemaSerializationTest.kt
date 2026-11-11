package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FunctionSchemaSerializationTest {
    @Test
    fun `function schemas omit absent keywords and preserve nested enum constraints`() {
        val request = LLMRequest.Chat(
            messages = emptyList(),
            functions = listOf(LLMRequest.Function(
                name = "OpenApplication",
                parameters = LLMRequest.Parameters(properties = mapOf(
                    "application" to LLMRequest.Property(type = "string"),
                    "options" to LLMRequest.Property(type = "array", items = LLMRequest.Property(
                        type = "object",
                        properties = mapOf("mode" to LLMRequest.Property(type = "string", enum = listOf("normal", "safe"))),
                    )),
                ), type = "object"),
            )),
        )

        val payload = restJsonMapper.readTree(restJsonMapper.writeValueAsString(request))
        val properties = payload["functions"][0]["parameters"]["properties"]
        assertFalse(properties["application"].has("enum"))
        assertFalse(properties["application"].has("description"))
        val mode = properties["options"]["items"]["properties"]["mode"]
        assertEquals(listOf("normal", "safe"), mode["enum"].map { it.asText() })
        assertFalse(mode.has("description"))
    }
}
