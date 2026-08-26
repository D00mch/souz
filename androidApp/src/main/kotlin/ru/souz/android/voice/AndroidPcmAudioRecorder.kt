package ru.souz.android.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.ui.host.UiAudioRecorder
import ru.souz.ui.host.UiAudioRecordingState
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

const val PCM_SAMPLE_RATE_HZ = 16_000

private const val CHUNK_SAMPLES = 320
private const val CHUNK_BYTES = CHUNK_SAMPLES * 2
private const val CHUNK_MS = CHUNK_SAMPLES * 1000L / PCM_SAMPLE_RATE_HZ
private const val LEVEL_LOG_INTERVAL_MS = 500L

class AndroidPcmAudioRecorder(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : UiAudioRecorder {
    private val l = LoggerFactory.getLogger(AndroidPcmAudioRecorder::class.java)
    private val appContext = context.applicationContext

    private val _audioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    override val audioFlow: Flow<ByteArray> = _audioFlow

    private val _recordingState = MutableStateFlow<UiAudioRecordingState>(UiAudioRecordingState.Idle)
    override val recordingState = _recordingState.asStateFlow()

    private val stopRequested = MutableStateFlow(false)
    private var captureJob: Job? = null

    private val _lastStats = MutableStateFlow(CaptureStats(CaptureOutcome.ERROR))
    val lastStats: CaptureStats
        get() = _lastStats.value

    val isCapturing: Boolean
        get() = captureJob?.isActive == true

    override suspend fun logState(): Nothing {
        recordingState.collect { l.info("Recording state: {}", it) }
        error("recordingState flow completed")
    }

    /** UI push-to-talk path: result is delivered through [audioFlow]. */
    override fun start(): Boolean {
        if (isCapturing) return false
        stopRequested.value = false
        _recordingState.value = UiAudioRecordingState.Starting
        captureJob = scope.launch { _audioFlow.emit(capture().pcm) }
        return true
    }

    /** Assistant session path: result is returned directly. */
    suspend fun captureUtterance(): CapturedAudio {
        if (isCapturing) {
            l.warn("Capture requested while another capture is active")
            return CapturedAudio(ByteArray(0), CaptureStats(CaptureOutcome.ERROR, error = "Микрофон занят"))
        }
        stopRequested.value = false
        _recordingState.value = UiAudioRecordingState.Starting
        val capture = scope.async { capture() }
        captureJob = capture
        return capture.await()
    }

    override fun stop() {
        stopRequested.value = true
    }

    fun abort() {
        stopRequested.value = true
        captureJob?.cancel()
    }

    @SuppressLint("MissingPermission")
    private suspend fun capture(): CapturedAudio {
        val vad = VadParams.read(appContext)
        l.info("Capture starting with {}", vad)

        val minBuffer = AudioRecord.getMinBufferSize(
            PCM_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return fail("AudioRecord.getMinBufferSize failed: $minBuffer")

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                PCM_SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, CHUNK_BYTES * 8),
            )
        }.getOrElse { return fail("AudioRecord creation failed: ${it.message}") }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return fail("AudioRecord is not initialized")
        }

        val captured = ByteArrayOutputStream()
        try {
            record.startRecording()
            _recordingState.value = UiAudioRecordingState.Recording

            val chunk = ByteArray(CHUNK_BYTES)
            var elapsedMs = 0L
            var speechMs = 0L
            var silenceMs = 0L
            var speechStarted = false
            var peakRms = 0
            var windowPeak = 0
            var windowMs = 0L

            while (currentCoroutineContext().isActive && !stopRequested.value) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) return fail("AudioRecord.read failed: $read")
                captured.write(chunk, 0, read)
                elapsedMs += CHUNK_MS

                val rms = rms(chunk, read)
                if (rms > peakRms) peakRms = rms
                if (rms > windowPeak) windowPeak = rms
                windowMs += CHUNK_MS
                if (windowMs >= LEVEL_LOG_INTERVAL_MS) {
                    l.info(
                        "Level: peakRms={} start={} continue={} speech={}ms at {}ms",
                        windowPeak, vad.silenceRms, vad.continuationRms, speechMs, elapsedMs,
                    )
                    windowPeak = 0
                    windowMs = 0
                }

                if (!speechStarted && rms > vad.silenceRms) speechStarted = true
                if (speechStarted && rms > vad.continuationRms) {
                    speechMs += CHUNK_MS
                    silenceMs = 0
                } else {
                    silenceMs += CHUNK_MS
                }

                val leadInExpired = !speechStarted && elapsedMs >= vad.leadInMs
                val endpointed = speechStarted && silenceMs >= vad.trailingSilenceMs
                if (leadInExpired || endpointed || elapsedMs >= vad.maxUtteranceMs) break
            }

            _recordingState.value = UiAudioRecordingState.Stopping
            val outcome = when {
                speechMs >= vad.minSpeechMs -> CaptureOutcome.SPEECH
                !speechStarted -> CaptureOutcome.NO_SPEECH
                else -> CaptureOutcome.TOO_SHORT
            }
            val stats = CaptureStats(outcome, speechMs, elapsedMs, peakRms)
            l.info(
                "Capture finished: outcome={} speech={}ms total={}ms peakRms={} threshold={}",
                outcome, speechMs, elapsedMs, peakRms, vad.silenceRms,
            )
            _lastStats.value = stats

            val pcm = captured.toByteArray()
            if (vad.dumpAudio) dumpAudio(pcm)

            val keepAudio = outcome == CaptureOutcome.SPEECH || vad.recognizeWithoutSpeech
            return CapturedAudio(if (keepAudio) pcm else ByteArray(0), stats)
        } finally {
            runCatching { record.stop() }
            record.release()
            _recordingState.value = UiAudioRecordingState.Idle
        }
    }

    private fun fail(message: String): CapturedAudio {
        l.error(message)
        _recordingState.value = UiAudioRecordingState.Error(message)
        _lastStats.value = CaptureStats(CaptureOutcome.ERROR, error = message)
        return CapturedAudio(ByteArray(0), _lastStats.value)
    }

    private fun dumpAudio(pcm: ByteArray) {
        runCatching {
            val dir = java.io.File(appContext.filesDir, "voice-dumps").apply { mkdirs() }
            val file = java.io.File(dir, "capture-${System.currentTimeMillis()}.wav")
            file.writeBytes(wavHeader(pcm.size) + pcm)
            l.info("Dumped capture to {}", file.absolutePath)
        }.onFailure { l.warn("Audio dump failed: {}", it.message) }
    }

    private fun wavHeader(pcmSize: Int): ByteArray {
        val byteRate = PCM_SAMPLE_RATE_HZ * 2
        val buffer = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + pcmSize)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(PCM_SAMPLE_RATE_HZ)
        buffer.putInt(byteRate)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(pcmSize)
        return buffer.array()
    }

    private fun rms(buffer: ByteArray, length: Int): Int {
        var sum = 0.0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample
            i += 2
        }
        val samples = length / 2
        return if (samples == 0) 0 else sqrt(sum / samples).toInt()
    }
}
