package ru.souz.llms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JsonSchemaPropertyMapsTest {

    @Test
    fun `array properties include unconstrained items only when requested`() {
        val property = LLMRequest.Property(
            type = "array",
            description = "ids",
            enum = listOf("a", "b"),
        )

        val withoutItems = property.toJsonSchemaMap()
        assertEquals("array", withoutItems["type"])
        assertEquals("ids", withoutItems["description"])
        assertEquals(listOf("a", "b"), withoutItems["enum"])
        assertFalse("items" in withoutItems)

        val withItems = property.toJsonSchemaMap(unconstrainedArrayItems = true)
        assertEquals(emptyMap<String, Any>(), withItems["items"])
        assertEquals(4, withItems.size)
    }

    @Test
    fun `optional fields are omitted when absent`() {
        val schema = LLMRequest.Property(type = "string").toJsonSchemaMap()
        assertEquals(mapOf("type" to "string"), schema)
        assertEquals(1, schema.size)
    }
}
