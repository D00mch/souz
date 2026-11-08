package ru.souz.tool.browser

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import ru.souz.tool.ShellException
import ru.souz.tool.ToolRunBashCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

class DefaultBrowserTest {
    private val command = "plutil -convert xml1 -o - ~/Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure.plist"

    @BeforeEach
    fun mockShell() {
        mockkObject(ToolRunBashCommand)
    }

    @AfterEach
    fun restoreShell() {
        unmockkObject(ToolRunBashCommand)
    }

    @Test
    fun `http handlers retain browser classification and multiline role matching`() {
        for ((bundleId, expected) in listOf(
            "com.google.CHROME" to BrowserType.CHROME,
            "com.apple.SaFaRi" to BrowserType.SAFARI,
            "org.mozilla.firefox" to BrowserType.OTHER,
            "com.example.chrome.safari" to BrowserType.CHROME,
        )) {
            every { ToolRunBashCommand.sh(command) } returns handler("http", bundleId)
            assertEquals(expected, ToolRunBashCommand.detectDefaultBrowser(), bundleId)
        }
    }

    @Test
    fun `missing http role falls back to Safari`() {
        for (xml in listOf("", "<plist/>", handler("https", "com.google.Chrome"), handler("http"))) {
            every { ToolRunBashCommand.sh(command) } returns xml
            assertEquals(BrowserType.SAFARI, ToolRunBashCommand.detectDefaultBrowser(), xml)
        }
    }

    @Test
    fun `scan skips unrelated schemes and incomplete http handlers`() {
        every { ToolRunBashCommand.sh(command) } returns (
            handler("https", "com.apple.Safari") +
                handler("http") + handler("http") +
                handler("http", "com.google.Chrome")
            )

        assertEquals(BrowserType.CHROME, ToolRunBashCommand.detectDefaultBrowser())
    }

    @Test
    fun `first role in first complete http handler wins`() {
        every { ToolRunBashCommand.sh(command) } returns """
            <dict>
                <key>LSHandlerURLScheme</key><string>http</string>
                <key>LSHandlerRoleAll</key><string>org.mozilla.firefox</string>
                <key>LSHandlerRoleAll</key><string>com.google.Chrome</string>
            </dict>
        """.trimIndent() + handler("http", "com.apple.Safari")

        assertEquals(BrowserType.OTHER, ToolRunBashCommand.detectDefaultBrowser())
    }

    @Test
    fun `repeated detection reads changed preferences and does not retain prior matches`() {
        val outputs = listOf(handler("http", "com.google.Chrome"), "", handler("http", "org.mozilla.firefox"))
        every { ToolRunBashCommand.sh(command) } returnsMany outputs

        assertEquals(BrowserType.CHROME, ToolRunBashCommand.detectDefaultBrowser())
        assertEquals(BrowserType.SAFARI, ToolRunBashCommand.detectDefaultBrowser())
        assertEquals(BrowserType.OTHER, ToolRunBashCommand.detectDefaultBrowser())
        verify(exactly = 3) { ToolRunBashCommand.sh(command) }
    }

    @Test
    fun `shell failure still propagates to caller`() {
        val failure = ShellException("synthetic plutil failure", 1)
        every { ToolRunBashCommand.sh(command) } throws failure

        assertSame(failure, assertFailsWith<ShellException> { ToolRunBashCommand.detectDefaultBrowser() })
    }

    private fun handler(scheme: String, bundleId: String? = null): String = """
        <dict>
            <key>LSHandlerURLScheme</key><string>$scheme</string>
            ${bundleId?.let { "<key>LSHandlerRoleAll</key>\n\t<string>$it</string>" }.orEmpty()}
        </dict>
    """.trimIndent()
}
