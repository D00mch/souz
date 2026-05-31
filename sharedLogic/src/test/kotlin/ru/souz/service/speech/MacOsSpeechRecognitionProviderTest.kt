package ru.souz.service.speech

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import ru.souz.db.SettingsProvider
import ru.souz.db.SettingsProviderImpl.Companion.REGION_RU
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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

        val error = assertFailsWith<LocalMacOsSpeechPermissionDeniedException> {
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

        val error = assertFailsWith<LocalMacOsSpeechAppBundleMissingUsageDescriptionException> {
            provider.recognize(byteArrayOf(1, 2))
        }

        assertEquals(
            "Local macOS speech recognition requires a macOS app bundle with NSSpeechRecognitionUsageDescription.",
            error.message
        )
        assertEquals(0, bridge.authorizationStatusCalls)
    }

    @Test
    fun `provider maps on device unsupported bridge error to explicit fatal error`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_RU

        val bridge = FakeMacOsSpeechBridge(
            status = MacOsSpeechAuthorizationStatus.AUTHORIZED,
            onRecognize = { _, _ ->
                throw IllegalStateException(
                    "LOCAL_MACOS_STT:ON_DEVICE_UNSUPPORTED:On-device speech recognition is not supported."
                )
            }
        )

        val provider = MacOsSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            bridge = bridge,
            isMacOsProvider = { true },
        )

        val error = assertFailsWith<LocalMacOsSpeechOnDeviceUnsupportedException> {
            provider.recognize(byteArrayOf(1, 2, 3, 4))
        }

        assertEquals(
            "On-device speech recognition is not supported.",
            error.message
        )
    }

    @Test
    fun `provider rejects too long pcm before calling bridge`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_RU

        val bridge = FakeMacOsSpeechBridge(
            status = MacOsSpeechAuthorizationStatus.AUTHORIZED,
            onRecognize = { _, _ -> "should not be called" }
        )

        val provider = MacOsSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            bridge = bridge,
            isMacOsProvider = { true },
        )

        val error = assertFailsWith<LocalMacOsSpeechAudioTooLongException> {
            provider.recognize(ByteArray(MAX_LOCAL_MACOS_PCM_BYTES + 1))
        }

        assertEquals(
            "Local macOS speech recognition supports up to 45 seconds per request.",
            error.message
        )
        assertEquals(0, bridge.recognizeCalls)
    }

    @Test
    fun `provider cancels in flight bridge recognition when coroutine is cancelled`() = runTest {
        val settingsProvider = mockk<SettingsProvider>()
        every { settingsProvider.regionProfile } returns REGION_RU

        val recognitionStarted = CompletableDeferred<Unit>()
        val releaseRecognition = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val bridge = FakeMacOsSpeechBridge(
            status = MacOsSpeechAuthorizationStatus.AUTHORIZED,
            onRecognize = { _, _ ->
                recognitionStarted.complete(Unit)
                runBlocking { releaseRecognition.await() }
                throw IllegalStateException("LOCAL_MACOS_STT:CANCELLED:Recognition cancelled.")
            },
            onCancelRecognition = {
                cancellationObserved.complete(Unit)
                releaseRecognition.complete(Unit)
            }
        )

        val provider = MacOsSpeechRecognitionProvider(
            settingsProvider = settingsProvider,
            bridge = bridge,
            isMacOsProvider = { true },
        )

        val recognizeJob = async {
            provider.recognize(byteArrayOf(1, 2, 3, 4))
        }

        try {
            awaitDeferred(recognitionStarted)

            recognizeJob.cancel(CancellationException("cancel recognition"))

            awaitDeferred(cancellationObserved)
            assertEquals(1, bridge.cancelRecognitionCalls)
            assertFailsWith<CancellationException> { recognizeJob.await() }
        } finally {
            releaseRecognition.complete(Unit)
        }
    }

    private class FakeMacOsSpeechBridge(
        private var status: MacOsSpeechAuthorizationStatus,
        private val hasSpeechRecognitionUsageDescription: Boolean = true,
        private val onRecognize: (FakeMacOsSpeechBridge.(Path, String) -> String)? = null,
        private val onCancelRecognition: (() -> Unit)? = null,
    ) : MacOsSpeechBridgeApi {
        lateinit var lastPath: Path
        var lastLocale: String? = null
        var lastBytes: ByteArray = byteArrayOf()
        var authorizationStatusCalls: Int = 0
        var recognizeCalls: Int = 0
        var cancelRecognitionCalls: Int = 0

        override fun hasSpeechRecognitionUsageDescription(): Boolean = hasSpeechRecognitionUsageDescription

        override fun authorizationStatus(): MacOsSpeechAuthorizationStatus {
            authorizationStatusCalls += 1
            return status
        }

        override fun requestAuthorizationIfNeeded() = Unit

        override fun recognizeWav(path: String, locale: String): String {
            recognizeCalls += 1
            return checkNotNull(onRecognize) { "recognizeWav should not be called in this test" }
                .invoke(this, Path.of(path), locale)
        }

        override fun cancelRecognition() {
            cancelRecognitionCalls += 1
            onCancelRecognition?.invoke()
        }
    }

    private suspend fun awaitDeferred(signal: CompletableDeferred<Unit>) {
        val deadlineMs = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadlineMs) {
            if (signal.isCompleted) {
                signal.await()
                return
            }
            withContext(Dispatchers.Default) { yield() }
        }
        error("Timed out waiting for deferred completion")
    }

    private companion object {
        const val MAX_LOCAL_MACOS_PCM_BYTES = 16_000 * 2 * 45
    }
}
