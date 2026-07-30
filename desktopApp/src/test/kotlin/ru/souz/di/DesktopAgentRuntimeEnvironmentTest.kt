package ru.souz.di

import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAgentRuntimeEnvironmentTest {
    @Test
    fun `runtime keeps startup locale and follows system time zone changes`() {
        val originalLocale = Locale.forLanguageTag("ru-RU")
        val previousLocale = Locale.getDefault()
        val previousDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY)
        val previousFormatLocale = Locale.getDefault(Locale.Category.FORMAT)
        val previousTimeZone = TimeZone.getDefault()

        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val runtimeEnvironment = DesktopAgentRuntimeEnvironment(originalLocale)

            Locale.setDefault(Locale.ENGLISH)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

            assertEquals(originalLocale, runtimeEnvironment.locale)
            assertEquals("Asia/Tokyo", runtimeEnvironment.zoneId.id)
        } finally {
            Locale.setDefault(previousLocale)
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplayLocale)
            Locale.setDefault(Locale.Category.FORMAT, previousFormatLocale)
            TimeZone.setDefault(previousTimeZone)
        }
    }
}
