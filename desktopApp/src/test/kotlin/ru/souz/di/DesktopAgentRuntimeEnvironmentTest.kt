package ru.souz.di

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import ru.souz.tool.ToolRunBashCommand
import ru.souz.tool.browser.BrowserType
import ru.souz.tool.browser.detectDefaultBrowser
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopAgentRuntimeEnvironmentTest {
    @Test
    fun `browser follows system preference changes and tolerates detection failure`() {
        mockkStatic("ru.souz.tool.browser.DefaultBrowserKt")
        try {
            every { ToolRunBashCommand.detectDefaultBrowser() } returnsMany listOf(BrowserType.SAFARI, BrowserType.CHROME)
            val environment = DesktopAgentRuntimeEnvironment(Locale.US)

            assertEquals("Safari", environment.defaultBrowserDisplayName)
            assertEquals("Google Chrome", environment.defaultBrowserDisplayName)
            every { ToolRunBashCommand.detectDefaultBrowser() } throws IllegalStateException("unavailable")
            assertNull(environment.defaultBrowserDisplayName)
        } finally {
            unmockkStatic("ru.souz.tool.browser.DefaultBrowserKt")
        }
    }

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
