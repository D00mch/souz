package ru.souz.ui.graphlog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphStepDetailsTest {

    @Test
    fun `extracts selected categories from step data`() {
        val categories = extractSelectedCategories(
            """{"selectedCategories":["APPLICATIONS","BROWSER"]}"""
        )

        assertEquals(listOf("APPLICATIONS", "BROWSER"), categories)
    }

    @Test
    fun `returns empty categories for missing malformed or non array data`() {
        assertEquals(emptyList(), extractSelectedCategories("{}"))
        assertEquals(emptyList(), extractSelectedCategories("""{"selectedCategories":"APPLICATIONS"}"""))
        assertEquals(emptyList(), extractSelectedCategories("{"))
    }

    @Test
    fun `detects classify step names case insensitively`() {
        assertTrue(isClassifyStep("classify"))
        assertTrue(isClassifyStep("Agent::Classify"))
        assertTrue(isClassifyStep("Классификатор"))
        assertFalse(isClassifyStep("Input -> History"))
    }
}
