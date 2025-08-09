package com.dumch.audio

import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.Float
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.ByteArray
import kotlin.FloatArray
import kotlin.RuntimeException
import kotlin.Short
import kotlin.String


/**
 * Wrapper around the whisper-jni library for simple speech recognition.
 */
object WhisperJniRecognizer {
    private val l = LoggerFactory.getLogger(WhisperJniRecognizer::class.java)
    // Link: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin`
    private val modelPath: String = System.getenv("WHISPER_MODEL_PATH") ?: "ggml-large-v3-turbo-q5_0.bin"

    init {
        WhisperJNI.loadLibrary() // load platform binaries
        WhisperJNI.setLibraryLogger(null) // capture/disable whisper.cpp log
    }

    fun recognize(audioIS: AudioInputStream): String {
        val whisper = WhisperJNI()
        val samples: FloatArray = readJFKFileSamples(audioIS)
        val ctx = whisper.init(Path.of(modelPath))
        val params = WhisperFullParams(WhisperSamplingStrategy.GREEDY).apply {
            this.language = "ru"
            this.nThreads = 4
            this.detectLanguage = false
            this.grammar
        }
        val result = whisper.full(ctx, params, samples, samples.size)
        if (result != 0) {
            throw RuntimeException("Transcription failed with code " + result)
        }
        val numSegments = whisper.fullNSegments(ctx)
        val text = whisper.fullGetSegmentText(ctx, 0)
        ctx.close() // free native memory, should be called when we don't need the context anymore.
        return text
    }

    // sample is a 16 bit int 16000hz little endian wav file
    private fun readJFKFileSamples(audioInputStream: AudioInputStream): FloatArray {
        // read all the available data to a little endian capture buffer
        val captureBuffer: ByteBuffer = ByteBuffer.allocate(audioInputStream.available())
        captureBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val read = audioInputStream.read(captureBuffer.array())
        if (read == -1) {
            throw IOException("Empty file")
        }
        // obtain the 16 int audio samples, short type in java
        val shortBuffer = captureBuffer.asShortBuffer()
        // transform the samples to f32 samples
        val samples = FloatArray(captureBuffer.capacity() / 2)
        var i = 0
        while (shortBuffer.hasRemaining()) {
            samples[i++] = Float.max(-1f, Float.min((shortBuffer.get().toFloat()) / Short.MAX_VALUE.toFloat(), 1f))
        }
        return samples
    }
}

fun recordWav(seconds: Int = 5): AudioInputStream {
    val format = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        44_100f,          // sample rate
        16,               // bits
        1,                // channels (mono)
        2,                // frame size = 2 bytes * 1 channel
        44_100f,          // frame rate
        false             // little-endian
    )

    val info = DataLine.Info(TargetDataLine::class.java, format)
    val line = AudioSystem.getLine(info) as TargetDataLine
    line.open(format)
    line.start()

    val frameSize = format.frameSize
    val framesToCapture = (seconds.toDouble() * format.sampleRate).toLong()
    val bytesToCapture = framesToCapture * frameSize

    val buf = ByteArray(4096)
    val out = ByteArrayOutputStream(bytesToCapture.toInt())
    var remaining = bytesToCapture

    while (remaining > 0) {
        val toRead = minOf(buf.size.toLong(), remaining).toInt()
        val read = line.read(buf, 0, toRead)
        if (read <= 0) break
        out.write(buf, 0, read)
        remaining -= read
    }

    line.stop()
    line.close()

    val audioBytes = out.toByteArray()
    val frames = audioBytes.size / frameSize
    return AudioInputStream(ByteArrayInputStream(audioBytes), format, frames.toLong())
}

fun main() = runBlocking {
    println("Recording 5 seconds of audio. Speak in Russian now.")
    val audioInputStream = recordWav()
    println("Recorded audio")
    val text = WhisperJniRecognizer.recognize(audioInputStream)
    println("Recognized text: $text")
}
