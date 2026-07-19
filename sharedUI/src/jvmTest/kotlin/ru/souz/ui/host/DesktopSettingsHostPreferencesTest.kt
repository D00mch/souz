package ru.souz.ui.host

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSettingsHostPreferencesTest {
    @Test
    fun `interface language follows system and applies persisted choices safely`() {
        withDefaultLocale("en-US") {
            Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("ru-RU"))
            assertFalse(preferences(saved = null).useEnglishInterface)
        }
        withDefaultLocale("ru-RU") {
            Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("en-Latn-US-u-nu-latn"))
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("de-DE"))
            assertTrue(preferences(saved = null).useEnglishInterface)

            val savedValues = mutableListOf<Boolean>()
            val preferences = DesktopSettingsHostPreferences(
                readUseEnglishInterface = { false },
                writeUseEnglishInterface = { savedValues += it },
            )

            preferences.applyInterfaceLanguage()

            assertEquals("ru-Latn-US-u-nu-latn", Locale.getDefault().toLanguageTag())
            assertEquals("de-DE", Locale.getDefault(Locale.Category.FORMAT).toLanguageTag())

            preferences.useEnglishInterface = true

            assertEquals(listOf(true), savedValues)
            assertEquals("en", Locale.getDefault().language)
        }
        withDefaultLocale(Locale.of("en", "US", "bad_variant")) {
            val preferences = preferences(saved = false)

            preferences.applyInterfaceLanguage()

            assertEquals("ru", Locale.getDefault().language)
        }
    }

    private fun preferences(saved: Boolean?) = DesktopSettingsHostPreferences(
        readUseEnglishInterface = { saved },
        writeUseEnglishInterface = {},
    )

    private inline fun <T> withDefaultLocale(languageTag: String, block: () -> T): T =
        withDefaultLocale(Locale.forLanguageTag(languageTag), block)

    private inline fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
        val previousLocale = Locale.getDefault()
        val previousDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
        val previousFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
        return try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previousLocale)
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplayLocale)
            Locale.setDefault(Locale.Category.FORMAT, previousFormatLocale)
        }
    }
}
