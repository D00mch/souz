package ru.souz.backend.http

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequestParsingTest {
    @Test
    fun `parseLocale canonicalizes legacy BCP47 language tags`() {
        assertEquals(Locale.forLanguageTag("he-IL"), parseLocale("iw-IL", fieldName = "locale"))
    }

    @Test
    fun `parseLocale accepts valid BCP47 tags with variants`() {
        assertEquals(Locale.forLanguageTag("de-CH-1901"), parseLocale("de-CH-1901", fieldName = "locale"))
    }

    @Test
    fun `parseLocale rejects malformed locale tags`() {
        val error = assertFailsWith<BackendV1Exception> {
            parseLocale("not-a-locale", fieldName = "locale")
        }

        assertEquals("invalid_request", error.code)
        assertTrue(error.message.contains("locale"))
    }
}
