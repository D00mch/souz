package ru.souz.service.speech

import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MacOsSpeechAnalyzerLiveTranscriptionProviderTest {

    @Test
    fun `parser returns empty list for blank raw events`() {
        assertEquals(emptyList(), parseLiveSpeechTranscriptEvents(""))
        assertEquals(emptyList(), parseLiveSpeechTranscriptEvents("\n"))
    }

    @Test
    fun `parser decodes final event`() {
        val events = parseLiveSpeechTranscriptEvents("1\t1200\t2500\t${encoded("hello world")}")

        assertEquals(
            listOf(
                LiveSpeechTranscriptEvent(
                    text = "hello world",
                    isFinal = true,
                    startedAtMs = 1200,
                    endedAtMs = 2500,
                )
            ),
            events,
        )
    }

    @Test
    fun `parser preserves repeated identical final events`() {
        val raw = listOf(
            "1\t100\t200\t${encoded("да")}",
            "1\t210\t300\t${encoded("да")}",
        ).joinToString("\n")

        assertEquals(
            listOf(
                LiveSpeechTranscriptEvent("да", isFinal = true, startedAtMs = 100, endedAtMs = 200),
                LiveSpeechTranscriptEvent("да", isFinal = true, startedAtMs = 210, endedAtMs = 300),
            ),
            parseLiveSpeechTranscriptEvents(raw),
        )
    }

    @Test
    fun `parser decodes volatile event with non ascii text and nullable timestamps`() {
        val events = parseLiveSpeechTranscriptEvents("0\t\t\t${encoded("привет мир")}")

        assertEquals(
            listOf(
                LiveSpeechTranscriptEvent(
                    text = "привет мир",
                    isFinal = false,
                    startedAtMs = null,
                    endedAtMs = null,
                )
            ),
            events,
        )
    }

    @Test
    fun `parser decodes event without timestamps`() {
        assertEquals(
            listOf(LiveSpeechTranscriptEvent("no timestamps", isFinal = true)),
            parseLiveSpeechTranscriptEvents("1\t\t\t${encoded("no timestamps")}"),
        )
    }

    @Test
    fun `parser rejects malformed input`() {
        assertFailsWith<LocalMacOsLiveSpeechUnavailableException> {
            parseLiveSpeechTranscriptEvents("1\tbad")
        }
    }

    @Test
    fun `provider is disabled on non macos hosts`() {
        val provider = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = FakeMacOsSpeechBridge(),
            isMacOsProvider = { false },
        )

        assertFalse(provider.enabled)
        assertFalse(provider.hasRequiredKey)
    }

    @Test
    fun `isSupported returns false when live symbol is missing`() = runTest {
        val provider = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = FakeMacOsSpeechBridge(
                onLiveIsSupported = { throw UnsatisfiedLinkError("old dylib") }
            ),
            isMacOsProvider = { true },
        )

        assertFalse(provider.isSupported("en-US"))
    }

    @Test
    fun `isSupported returns false when bridge does not support live methods`() = runTest {
        val provider = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = FakeMacOsSpeechBridge(
                onLiveIsSupported = { throw UnsupportedOperationException("not supported") }
            ),
            isMacOsProvider = { true },
        )

        assertFalse(provider.isSupported("en-US"))
    }

    @Test
    fun `live bridge mapper preserves machine readable code and payload`() {
        val error = mapLiveBridgeError(
            IllegalStateException("LOCAL_MACOS_LIVE_STT:UNSUPPORTED_LOCALE:ru-RU")
        )

        assertTrue(error is LocalMacOsLiveSpeechUnsupportedException)
        assertEquals("UNSUPPORTED_LOCALE: ru-RU", error.message)
    }

    @Test
    fun `live bridge mapper treats unsupported configuration as unsupported live speech`() {
        val error = mapLiveBridgeError(
            IllegalStateException(
                "LOCAL_MACOS_LIVE_STT:UNSUPPORTED_CONFIGURATION:ru-RU; AssetInventory status is unsupported"
            )
        )

        assertTrue(error is LocalMacOsLiveSpeechUnsupportedException)
        assertEquals(
            "UNSUPPORTED_CONFIGURATION: ru-RU; AssetInventory status is unsupported",
            error.message,
        )
    }

    @Test
    fun `start requests speech authorization when not determined`() = runTest {
        val bridge = FakeMacOsSpeechBridge(
            initialAuthorizationStatus = MacOsSpeechAuthorizationStatus.NOT_DETERMINED,
        )
        val provider = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = bridge,
            isMacOsProvider = { true },
        )

        provider.start("en-US")

        assertEquals(1, bridge.requestAuthorizationCalls)
        assertEquals("en-US", bridge.livePrepareAssetsLocale)
        assertEquals("en-US", bridge.liveStartLocale)
    }

    @Test
    fun `start fails before authorization when speech usage description is missing`() = runTest {
        val bridge = FakeMacOsSpeechBridge(
            hasSpeechRecognitionUsageDescription = false,
            initialAuthorizationStatus = MacOsSpeechAuthorizationStatus.NOT_DETERMINED,
        )
        val provider = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = bridge,
            isMacOsProvider = { true },
        )

        val error = assertFailsWith<LocalMacOsLiveSpeechUnavailableException> {
            provider.start("en-US")
        }

        assertEquals(
            "Local macOS speech recognition requires a macOS app bundle with NSSpeechRecognitionUsageDescription.",
            error.message,
        )
        assertEquals(0, bridge.authorizationStatusCalls)
        assertEquals(0, bridge.requestAuthorizationCalls)
        assertEquals(null, bridge.livePrepareAssetsLocale)
        assertEquals(null, bridge.liveStartLocale)
    }

    @Test
    fun `start maps denied speech authorization before native live start`() = runTest {
        val bridge = FakeMacOsSpeechBridge(
            initialAuthorizationStatus = MacOsSpeechAuthorizationStatus.DENIED,
        )
        val provider = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = bridge,
            isMacOsProvider = { true },
        )

        assertFailsWith<LocalMacOsLiveSpeechPermissionDeniedException> {
            provider.start("en-US")
        }
        assertEquals(null, bridge.livePrepareAssetsLocale)
        assertEquals(null, bridge.liveStartLocale)
    }

    @Test
    fun `start prepares assets before native live start`() = runTest {
        val bridge = FakeMacOsSpeechBridge()
        val provider = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = bridge,
            isMacOsProvider = { true },
        )

        provider.start("ru-RU")

        assertEquals("ru-RU", bridge.livePrepareAssetsLocale)
        assertEquals("ru-RU", bridge.liveStartLocale)
    }

    @Test
    fun `start maps asset preparation errors before native live start`() = runTest {
        val bridge = FakeMacOsSpeechBridge(
            prepareAssetsError = IllegalStateException(
                "LOCAL_MACOS_LIVE_STT:MODEL_UNAVAILABLE:SpeechTranscriber model assets are not installed."
            ),
        )
        val provider = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = bridge,
            isMacOsProvider = { true },
        )

        assertFailsWith<LocalMacOsLiveSpeechModelUnavailableException> {
            provider.start("ru-RU")
        }
        assertEquals("ru-RU", bridge.livePrepareAssetsLocale)
        assertEquals(null, bridge.liveStartLocale)
    }

    @Test
    fun `start creates native session`() = runTest {
        val bridge = FakeMacOsSpeechBridge()
        val provider = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = bridge,
            isMacOsProvider = { true },
        )

        provider.start("en-US")

        assertEquals("en-US", bridge.liveStartLocale)
    }

    @Test
    fun `session acceptPcm passes expected pcm format`() = runTest {
        val bridge = FakeMacOsSpeechBridge()
        val session = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = bridge,
            isMacOsProvider = { true },
        ).start("en-US")

        session.acceptPcm(byteArrayOf(1, 2, 3, 4))

        assertEquals(42L, bridge.lastAcceptedSessionId)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), bridge.lastAcceptedAudio)
        assertEquals(16_000, bridge.lastSampleRateHz)
        assertEquals(1, bridge.lastChannels)
        assertEquals(16, bridge.lastBitsPerSample)
    }

    @Test
    fun `session poll and finalize parse events and close the session`() = runTest {
        val bridge = FakeMacOsSpeechBridge(
            pollRaw = "0\t\t\t${encoded("partial")}",
            finalizeRaw = "1\t10\t20\t${encoded("final")}",
        )
        val session = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = bridge,
            isMacOsProvider = { true },
        ).start("en-US")

        assertEquals(
            listOf(LiveSpeechTranscriptEvent("partial", isFinal = false)),
            session.pollEvents(),
        )
        assertEquals(
            listOf(LiveSpeechTranscriptEvent("final", isFinal = true, startedAtMs = 10, endedAtMs = 20)),
            session.finalizeAndFinish(),
        )
        assertFailsWith<LocalMacOsLiveSpeechUnavailableException> {
            session.acceptPcm(byteArrayOf(1))
        }
    }

    @Test
    fun `session poll returns repeated identical final events`() = runTest {
        val raw = listOf(
            "1\t10\t20\t${encoded("да")}",
            "1\t30\t40\t${encoded("да")}",
        ).joinToString("\n")
        val session = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = FakeMacOsSpeechBridge(pollRaw = raw),
            isMacOsProvider = { true },
        ).start("ru-RU")

        assertEquals(
            listOf(
                LiveSpeechTranscriptEvent("да", isFinal = true, startedAtMs = 10, endedAtMs = 20),
                LiveSpeechTranscriptEvent("да", isFinal = true, startedAtMs = 30, endedAtMs = 40),
            ),
            session.pollEvents(),
        )
    }

    @Test
    fun `session cancel is idempotent`() = runTest {
        val bridge = FakeMacOsSpeechBridge()
        val session = MacOsSpeechAnalyzerLiveTranscriptionProvider(
            bridge = bridge,
            isMacOsProvider = { true },
        ).start("en-US")

        session.cancel()
        session.cancel()

        assertEquals(1, bridge.liveCancelCalls)
    }

    private class FakeMacOsSpeechBridge(
        private val onLiveIsSupported: (String) -> Boolean = { true },
        private val pollRaw: String = "",
        private val finalizeRaw: String = "",
        private val prepareAssetsError: Throwable? = null,
        private val hasSpeechRecognitionUsageDescription: Boolean = true,
        initialAuthorizationStatus: MacOsSpeechAuthorizationStatus = MacOsSpeechAuthorizationStatus.AUTHORIZED,
    ) : MacOsSpeechBridgeApi {
        var livePrepareAssetsLocale: String? = null
        var liveStartLocale: String? = null
        var lastAcceptedSessionId: Long? = null
        var lastAcceptedAudio: ByteArray = byteArrayOf()
        var lastSampleRateHz: Int? = null
        var lastChannels: Int? = null
        var lastBitsPerSample: Int? = null
        var liveCancelCalls: Int = 0
        var requestAuthorizationCalls: Int = 0
        var authorizationStatusCalls: Int = 0
        private var authorizationStatus: MacOsSpeechAuthorizationStatus = initialAuthorizationStatus

        override fun hasSpeechRecognitionUsageDescription(): Boolean = hasSpeechRecognitionUsageDescription

        override fun authorizationStatus(): MacOsSpeechAuthorizationStatus {
            authorizationStatusCalls += 1
            return authorizationStatus
        }

        override fun requestAuthorizationIfNeeded() {
            requestAuthorizationCalls += 1
            if (authorizationStatus == MacOsSpeechAuthorizationStatus.NOT_DETERMINED) {
                authorizationStatus = MacOsSpeechAuthorizationStatus.AUTHORIZED
            }
        }

        override fun recognizeWav(path: String, locale: String): String = error("recognizeWav should not be called")

        override fun cancelRecognition() = Unit

        override fun liveIsSupported(locale: String): Boolean = onLiveIsSupported(locale)

        override fun livePrepareAssets(locale: String) {
            livePrepareAssetsLocale = locale
            prepareAssetsError?.let { throw it }
        }

        override fun liveStart(locale: String): Long {
            liveStartLocale = locale
            return 42L
        }

        override fun liveAcceptPcm(
            sessionId: Long,
            audio: ByteArray,
            sampleRateHz: Int,
            channels: Int,
            bitsPerSample: Int,
        ) {
            lastAcceptedSessionId = sessionId
            lastAcceptedAudio = audio
            lastSampleRateHz = sampleRateHz
            lastChannels = channels
            lastBitsPerSample = bitsPerSample
        }

        override fun livePollEvents(sessionId: Long): String = pollRaw

        override fun liveFinalizeAndFinish(sessionId: Long): String = finalizeRaw

        override fun liveCancel(sessionId: Long) {
            liveCancelCalls += 1
        }
    }

    private companion object {
        fun encoded(text: String): String =
            Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    }
}
