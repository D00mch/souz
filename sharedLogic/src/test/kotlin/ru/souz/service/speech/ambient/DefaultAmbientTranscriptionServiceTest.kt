package ru.souz.service.speech.ambient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ru.souz.service.speech.LiveSpeechTranscriptEvent
import ru.souz.service.speech.LiveSpeechTranscriptionProvider
import ru.souz.service.speech.LiveSpeechTranscriptionSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAmbientTranscriptionServiceTest {

    @Test
    fun `start returns live backend unavailable without starting microphone or legacy fallback`() = runTest {
        val liveProvider = FakeLiveSpeechTranscriptionProvider(supported = false)
        val audioSource = FakePcmAudioFrameSource(frames = flow { emit(byteArrayOf(1)) })
        val service = service(liveProvider = liveProvider, audioSource = audioSource, scope = this)

        val result = service.start("en-US")

        assertEquals(AmbientSpeechAvailability.LiveBackendUnavailable, result)
        assertEquals(AmbientTranscriptionState.Stopped, service.state.value)
        assertEquals(0, audioSource.startCalls)
        assertEquals(0, liveProvider.startCalls)
    }

    @Test
    fun `start returns microphone unavailable when pcm source cannot start`() = runTest {
        val liveProvider = FakeLiveSpeechTranscriptionProvider(supported = true)
        val audioSource = FakePcmAudioFrameSource(
            startResult = false,
            frames = flow { emit(byteArrayOf(1)) },
        )
        val service = service(liveProvider = liveProvider, audioSource = audioSource, scope = this)

        val result = service.start("en-US")

        assertEquals(AmbientSpeechAvailability.MicrophoneUnavailable, result)
        assertEquals(AmbientTranscriptionState.Stopped, service.state.value)
        assertEquals(1, audioSource.startCalls)
        assertEquals(0, liveProvider.startCalls)
    }

    @Test
    fun `service feeds pcm frames polls live events and stores transcript snapshot`() = runTest {
        val session = FakeLiveSpeechTranscriptionSession(
            pollEvents = listOf(
                listOf(LiveSpeechTranscriptEvent("draft", isFinal = false, startedAtMs = 10, endedAtMs = 20)),
            ),
            finalizeEvents = listOf(
                LiveSpeechTranscriptEvent("final", isFinal = true, startedAtMs = 10, endedAtMs = 40),
            ),
        )
        val liveProvider = FakeLiveSpeechTranscriptionProvider(supported = true, session = session)
        val audioSource = FakePcmAudioFrameSource(frames = flow { emit(byteArrayOf(1, 2, 3, 4)) })
        val service = service(liveProvider = liveProvider, audioSource = audioSource, scope = this)
        val emitted = mutableListOf<AmbientTranscriptEvent>()
        val collectJob = launch {
            service.transcriptEvents.take(2).toList(emitted)
        }

        val result = service.start("en-US")
        advanceUntilIdle()

        assertEquals(AmbientSpeechAvailability.Available, result)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), session.acceptedChunks.single())
        assertEquals(1, session.pollCalls)
        assertEquals(1, session.finalizeCalls)
        assertEquals(listOf("draft", "final"), emitted.map { it.text })
        assertEquals(listOf(false, true), emitted.map { it.isFinal })
        assertTrue(emitted.all { it.id.isNotBlank() })
        assertEquals(listOf("final"), service.snapshot().finalEvents.map { it.text })
        assertEquals(null, service.snapshot().currentVolatile)
        assertEquals(AmbientTranscriptionState.Stopped, service.state.value)
        collectJob.cancel()
    }

    @Test
    fun `start reports already running while listener is active`() = runTest {
        val session = FakeLiveSpeechTranscriptionSession()
        val liveProvider = FakeLiveSpeechTranscriptionProvider(supported = true, session = session)
        val audioSource = FakePcmAudioFrameSource(frames = flow { awaitCancellation() })
        val service = service(liveProvider = liveProvider, audioSource = audioSource, scope = this)

        assertEquals(AmbientSpeechAvailability.Available, service.start("en-US"))
        assertEquals(AmbientSpeechAvailability.AlreadyRunning, service.start("en-US"))

        service.stop()
    }

    @Test
    fun `stop cancels active live session and microphone source`() = runTest {
        val session = FakeLiveSpeechTranscriptionSession()
        val liveProvider = FakeLiveSpeechTranscriptionProvider(supported = true, session = session)
        val audioSource = FakePcmAudioFrameSource(frames = flow { awaitCancellation() })
        val service = service(liveProvider = liveProvider, audioSource = audioSource, scope = this)

        assertEquals(AmbientSpeechAvailability.Available, service.start("en-US"))
        assertIs<AmbientTranscriptionState.Listening>(service.state.value)

        service.stop()
        advanceUntilIdle()

        assertEquals(1, session.cancelCalls)
        assertEquals(1, audioSource.stopCalls)
        assertEquals(AmbientTranscriptionState.Stopped, service.state.value)
    }

    @Test
    fun `live session failure stops microphone and exposes error state`() = runTest {
        val session = FakeLiveSpeechTranscriptionSession(acceptError = IllegalStateException("live failed"))
        val liveProvider = FakeLiveSpeechTranscriptionProvider(supported = true, session = session)
        val audioSource = FakePcmAudioFrameSource(frames = flow { emit(byteArrayOf(1, 2)) })
        val service = service(liveProvider = liveProvider, audioSource = audioSource, scope = this)

        assertEquals(AmbientSpeechAvailability.Available, service.start("en-US"))
        advanceUntilIdle()

        val state = assertIs<AmbientTranscriptionState.Error>(service.state.value)
        assertEquals("live failed", state.message)
        assertEquals(1, audioSource.stopCalls)
        assertEquals(1, session.cancelCalls)
    }

    private fun service(
        liveProvider: LiveSpeechTranscriptionProvider,
        audioSource: PcmAudioFrameSource,
        scope: CoroutineScope,
    ): DefaultAmbientTranscriptionService = DefaultAmbientTranscriptionService(
        liveSpeechProvider = liveProvider,
        audioSource = audioSource,
        scope = scope,
        buffer = AmbientTranscriptBuffer(maxFinalEvents = 10, ttlMs = 10_000, clock = { 1_000L }),
        clock = { 1_000L },
    )

    private class FakeLiveSpeechTranscriptionProvider(
        private val supported: Boolean,
        private val session: FakeLiveSpeechTranscriptionSession = FakeLiveSpeechTranscriptionSession(),
    ) : LiveSpeechTranscriptionProvider {
        var startCalls: Int = 0

        override val enabled: Boolean = true
        override val hasRequiredKey: Boolean = true

        override suspend fun isSupported(locale: String): Boolean = supported

        override suspend fun start(locale: String): LiveSpeechTranscriptionSession {
            startCalls += 1
            return session
        }
    }

    private class FakeLiveSpeechTranscriptionSession(
        private val pollEvents: List<List<LiveSpeechTranscriptEvent>> = emptyList(),
        private val finalizeEvents: List<LiveSpeechTranscriptEvent> = emptyList(),
        private val acceptError: Throwable? = null,
    ) : LiveSpeechTranscriptionSession {
        val acceptedChunks: MutableList<ByteArray> = mutableListOf()
        var pollCalls: Int = 0
        var finalizeCalls: Int = 0
        var cancelCalls: Int = 0

        override suspend fun acceptPcm(audio: ByteArray) {
            acceptError?.let { throw it }
            acceptedChunks += audio
        }

        override suspend fun pollEvents(): List<LiveSpeechTranscriptEvent> =
            pollEvents.getOrElse(pollCalls++) { emptyList() }

        override suspend fun finalizeAndFinish(): List<LiveSpeechTranscriptEvent> {
            finalizeCalls += 1
            return finalizeEvents
        }

        override suspend fun cancel() {
            cancelCalls += 1
        }
    }

    private class FakePcmAudioFrameSource(
        private val startResult: Boolean = true,
        private val frames: Flow<ByteArray>,
    ) : PcmAudioFrameSource {
        var startCalls: Int = 0
        var stopCalls: Int = 0

        override val sampleRateHz: Int = 16_000
        override val channels: Int = 1
        override val bitsPerSample: Int = 16

        override fun frames(): Flow<ByteArray> = frames

        override suspend fun start(): Boolean {
            startCalls += 1
            return startResult
        }

        override suspend fun stop() {
            stopCalls += 1
        }
    }
}
