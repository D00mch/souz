package ru.souz.ui.main.usecases

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacOsSpeechRecognitionProviderTest {

    @Test
    fun `provider writes pcm to wav and returns bridge text`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_RU

        val bridge = FakeMacOsSpeechBridge(
            status = MacOsSpeechAuthorizationStatus.AUTHORIZED,
            onRecognize = { path, locale ->
                lastPath = path
                lastLocale = locale
                lastBytes = Files.readAllBytes(path)
                " распознанный текст "
            }
        )

        val provider = MacOsSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            bridge = bridge,
            isMacOsProvider = { true },
        )

        val rawPcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val recognized = provider.recognize(rawPcm)

        assertEquals("распознанный текст", recognized)
        assertEquals("ru-RU", bridge.lastLocale)
        assertTrue(bridge.lastBytes.decodeToString(0, 4) == "RIFF")
        assertTrue(bridge.lastBytes.decodeToString(8, 12) == "WAVE")
        assertContentEquals(rawPcm, bridge.lastBytes.copyOfRange(44, bridge.lastBytes.size))
        assertFalse(Files.exists(bridge.lastPath))
    }

    @Test
    fun `provider maps denied authorization to explicit local error`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_RU

        val bridge = FakeMacOsSpeechBridge(
            status = MacOsSpeechAuthorizationStatus.DENIED,
        )

        val provider = MacOsSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            bridge = bridge,
            isMacOsProvider = { true },
        )

        val error = kotlin.test.assertFailsWith<LocalMacOsSpeechPermissionDeniedException> {
            provider.recognize(byteArrayOf(1, 2))
        }

        assertEquals("Speech recognition permission denied.", error.message)
    }

    @Test
    fun `provider fails fast when speech usage description is missing`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_RU

        val bridge = FakeMacOsSpeechBridge(
            status = MacOsSpeechAuthorizationStatus.AUTHORIZED,
            hasSpeechRecognitionUsageDescription = false,
        )

        val provider = MacOsSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            bridge = bridge,
            isMacOsProvider = { true },
        )

        val error = kotlin.test.assertFailsWith<LocalMacOsSpeechAppBundleMissingUsageDescriptionException> {
            provider.recognize(byteArrayOf(1, 2))
        }

        assertEquals(
            "Local macOS speech recognition requires a macOS app bundle with NSSpeechRecognitionUsageDescription.",
            error.message
        )
        assertEquals(0, bridge.authorizationStatusCalls)
    }

    private class FakeMacOsSpeechBridge(
        private var status: MacOsSpeechAuthorizationStatus,
        private val hasSpeechRecognitionUsageDescription: Boolean = true,
        private val onRecognize: (FakeMacOsSpeechBridge.(Path, String) -> String)? = null,
    ) : MacOsSpeechBridgeApi {
        lateinit var lastPath: Path
        var lastLocale: String? = null
        var lastBytes: ByteArray = byteArrayOf()
        var authorizationStatusCalls: Int = 0

        override fun hasSpeechRecognitionUsageDescription(): Boolean = hasSpeechRecognitionUsageDescription

        override fun authorizationStatus(): MacOsSpeechAuthorizationStatus {
            authorizationStatusCalls += 1
            return status
        }

        override fun requestAuthorizationIfNeeded() = Unit

        override fun recognizeWav(path: String, locale: String): String =
            checkNotNull(onRecognize) { "recognizeWav should not be called in this test" }
                .invoke(this, Path.of(path), locale)
    }
}
