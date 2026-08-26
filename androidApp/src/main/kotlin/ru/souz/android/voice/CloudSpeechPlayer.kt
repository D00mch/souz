package ru.souz.android.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import ru.souz.service.speech.SpeechSynthesisProvider
import ru.souz.ui.host.UiSpeechPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val CHUNK_WRITE_BYTES = 4096
private const val SYNTHESIS_TIMEOUT_MS = 60_000L
private const val FALLBACK_SAMPLE_RATE_HZ = 24_000

class CloudSpeechPlayer(
    private val synthesisProvider: SpeechSynthesisProvider,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : UiSpeechPlayer {
    private val l = LoggerFactory.getLogger(CloudSpeechPlayer::class.java)

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val queue = Channel<String>(capacity = Channel.UNLIMITED)
    private var activeTrack: AudioTrack? = null

    init {
        scope.launch {
            for (text in queue) {
                _isSpeaking.value = true
                runCatching { speak(text) }
                    .onFailure { l.warn("Speech synthesis failed: {}", it.message) }
                _isSpeaking.value = false
            }
        }
    }

    override fun queue(text: String, speed: Int?) {
        if (text.isBlank()) {
            l.warn("Skipping speech: text is blank")
            return
        }
        if (!synthesisProvider.hasRequiredKey) {
            l.warn("Skipping speech: synthesis key is not configured")
            return
        }
        l.info("Queued {} chars for speech", text.length)
        queue.trySend(text.trim())
    }

    override fun clearQueue() {
        while (queue.tryReceive().isSuccess) Unit
        activeTrack?.runCatching { pause(); flush(); stop() }
        _isSpeaking.value = false
    }

    override fun playTextRand(speed: Int, vararg texts: String) {
        texts.randomOrNull()?.let { queue(it) }
    }

    override fun playMacPing() = tone(ToneGenerator.TONE_PROP_BEEP, 120)

    override fun playMacPingMsg() = tone(ToneGenerator.TONE_PROP_ACK, 150)

    override fun chooseVoice() = Unit

    private suspend fun speak(text: String) {
        val raw = withTimeout(SYNTHESIS_TIMEOUT_MS) { synthesisProvider.synthesize(text) }
        val audio = raw.decodeAudio()
        if (audio.pcm.isEmpty()) {
            l.warn("Synthesis returned {} bytes but no decodable PCM", raw.size)
            return
        }

        val channelMask =
            if (audio.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audio.sampleRateHz)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(audio.sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
                    .coerceAtLeast(8192),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        l.info("Speaking {} bytes at {} Hz, {} ch", audio.pcm.size, audio.sampleRateHz, audio.channels)
        activeTrack = track
        try {
            track.play()
            var offset = 0
            while (offset < audio.pcm.size && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val written = track.write(audio.pcm, offset, minOf(CHUNK_WRITE_BYTES, audio.pcm.size - offset))
                if (written <= 0) break
                offset += written
            }
            val totalFrames = audio.pcm.size / (2 * audio.channels)
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING && track.playbackHeadPosition < totalFrames) {
                delay(50)
            }
        } finally {
            activeTrack = null
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun tone(type: Int, durationMs: Int) {
        scope.launch {
            runCatching {
                val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                generator.startTone(type, durationMs)
                delay(durationMs.toLong() + 50)
                generator.release()
            }.onFailure { l.warn("Tone playback failed: {}", it.message) }
        }
    }
}

private data class DecodedAudio(
    val pcm: ByteArray,
    val sampleRateHz: Int,
    val channels: Int,
)

/** RIFF payloads are parsed; anything else is treated as raw 24 kHz mono PCM. */
private fun ByteArray.decodeAudio(): DecodedAudio {
    if (size < 44 || String(this, 0, 4, Charsets.US_ASCII) != "RIFF") {
        return DecodedAudio(this, FALLBACK_SAMPLE_RATE_HZ, 1)
    }
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    var sampleRateHz = FALLBACK_SAMPLE_RATE_HZ
    var channels = 1
    var offset = 12
    while (offset + 8 <= size) {
        val chunkId = String(this, offset, 4, Charsets.US_ASCII)
        val chunkSize = buffer.getInt(offset + 4)
        when (chunkId) {
            "fmt " -> {
                channels = buffer.getShort(offset + 10).toInt().coerceAtLeast(1)
                sampleRateHz = buffer.getInt(offset + 12)
            }

            "data" -> {
                val start = offset + 8
                val length = chunkSize.coerceAtMost(size - start)
                val pcm = if (length <= 0) ByteArray(0) else copyOfRange(start, start + length)
                return DecodedAudio(pcm, sampleRateHz, channels)
            }
        }
        offset += 8 + chunkSize + (chunkSize and 1)
    }
    return DecodedAudio(this, sampleRateHz, channels)
}
