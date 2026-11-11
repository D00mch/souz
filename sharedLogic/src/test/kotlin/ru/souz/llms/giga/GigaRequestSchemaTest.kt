package ru.souz.llms.giga

import ru.souz.llms.LLMRequest
import ru.souz.llms.restJsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GigaRequestSchemaTest {
    @Test
    fun `generic Skill arguments remain open objects on the Giga wire`() {
        val request = LLMRequest.Chat(
            messages = emptyList(),
            functions = listOf(LLMRequest.Function(
                name = "RunSkillCommand",
                parameters = LLMRequest.Parameters(type = "object", properties = mapOf(
                    "skillId" to LLMRequest.Property("string"),
                    "arguments" to LLMRequest.Property("object"),
                    "closed" to LLMRequest.Property("object", additionalProperties = false),
                    "rows" to LLMRequest.Property("array"),
                )),
            )),
        )

        val payload = restJsonMapper.readTree(restJsonMapper.writeValueAsString(request.toGigaChatRequest()))
        val properties = payload["functions"][0]["parameters"]["properties"]
        assertTrue(properties["arguments"]["additionalProperties"].asBoolean())
        assertFalse(properties["closed"]["additionalProperties"].asBoolean())
        assertTrue(properties["rows"]["items"]["additionalProperties"].asBoolean())
        assertEquals("object", properties["rows"]["items"]["type"].asText())
        assertFalse(properties["skillId"].has("enum"))
    }
}
