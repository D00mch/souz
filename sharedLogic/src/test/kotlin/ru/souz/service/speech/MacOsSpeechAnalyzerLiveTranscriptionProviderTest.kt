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
    ) : MacOsSpeechBridgeApi {
        var liveStartLocale: String? = null
        var lastAcceptedSessionId: Long? = null
        var lastAcceptedAudio: ByteArray = byteArrayOf()
        var lastSampleRateHz: Int? = null
        var lastChannels: Int? = null
        var lastBitsPerSample: Int? = null
        var liveCancelCalls: Int = 0

        override fun hasSpeechRecognitionUsageDescription(): Boolean = true

        override fun authorizationStatus(): MacOsSpeechAuthorizationStatus =
            MacOsSpeechAuthorizationStatus.AUTHORIZED

        override fun requestAuthorizationIfNeeded() = Unit

        override fun recognizeWav(path: String, locale: String): String = error("recognizeWav should not be called")

        override fun cancelRecognition() = Unit

        override fun liveIsSupported(locale: String): Boolean = onLiveIsSupported(locale)

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
