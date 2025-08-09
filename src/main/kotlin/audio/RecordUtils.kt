package com.dumch.audio

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ws.schild.jave.Encoder
import ws.schild.jave.MultimediaObject
import ws.schild.jave.encode.AudioAttributes
import ws.schild.jave.encode.EncodingAttributes

/**
 * Simple utility that records the microphone, encodes to Ogg/Opus and returns the result as ByteArray.
 */
object InMemoryOpusRecorder {

    private var line: TargetDataLine? = null
    private var format: AudioFormat? = null
    private var rawOut: ByteArrayOutputStream? = null
    private var preBuffer: CircularByteBuffer? = null
    private var captureJob: Job? = null
    @Volatile private var isRecording: Boolean = false

    /**
     * Open the microphone line and begin continuously capturing audio into a
     * small circular buffer. Call this once at start-up or shortly before the
     * user is expected to trigger recording.
     */
    fun prepare(
        scope: CoroutineScope,
        sampleRate: Float = 44_100f,
        channels: Int = 1,
        sampleSizeBits: Int = 16,
        preBufferMillis: Int = 2_000
    ) {
        if (line != null) return

        val fmt = AudioFormat(sampleRate, sampleSizeBits, channels, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) {
            println("ERROR: Line not supported for format: $fmt")
            throw LineUnavailableException("Line not supported for format: $fmt")
        }

        val target = AudioSystem.getLine(info) as TargetDataLine
        target.open(fmt)
        target.start()

        line = target
        format = fmt

        val bytesPerSecond = (fmt.frameSize * fmt.sampleRate).toInt()
        preBuffer = CircularByteBuffer(bytesPerSecond * preBufferMillis / 1_000)

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            while (isActive) {
                val read = target.read(buffer, 0, buffer.size)
                if (read > 0) {
                    preBuffer?.write(buffer, 0, read)
                    if (isRecording) {
                        rawOut?.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    /** Begin writing audio data (including pre-buffered audio) to the main stream. */
    fun startRecording() {
        if (line == null) {
            throw IllegalStateException("Recorder not prepared")
        }
        if (isRecording) return

        rawOut = ByteArrayOutputStream()
        preBuffer?.toByteArray()?.let { rawOut?.write(it) }
        isRecording = true
    }

    suspend fun stopRecording(): ByteArray {
        val target = line
        if (target != null) {
            target.stop()
            val buffer = ByteArray(4096)
            var available = target.available()
            while (available > 0) {
                val toRead = minOf(buffer.size, available)
                val read = target.read(buffer, 0, toRead)
                if (read > 0) {
                    rawOut?.write(buffer, 0, read)
                }
                available = target.available()
            }
            isRecording = false
            target.start()
        } else {
            isRecording = false
        }

        val fmt = format
        val rawBytes = rawOut?.toByteArray() ?: ByteArray(0)
        rawOut = null
        if (fmt == null) return rawBytes

        val frames = rawBytes.size / fmt.frameSize
        val wavBOS = ByteArrayOutputStream()
        val ais = AudioInputStream(
            ByteArrayInputStream(rawBytes),
            fmt,
            frames.toLong()
        )
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavBOS)
        return wavBOS.toByteArray()
    }

    suspend fun recordPcm(
        seconds: Int,
        sampleRate: Float = 44_100f,
        channels: Int = 1,
        sampleSizeBits: Int = 16
    ): ByteArray {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        prepare(scope, sampleRate, channels, sampleSizeBits)
        startRecording()
        delay(seconds * 1_000L)
        val data = stopRecording()
        scope.cancel()
        return data
    }

    /** Stop capturing and close the microphone line. */
    fun shutdown() {
        captureJob?.cancel()
        captureJob = null
        line?.stop()
        line?.close()
        line = null
    }

    /** Simple byte-based circular buffer. */
    private class CircularByteBuffer(private val capacity: Int) {
        private val buffer = ByteArray(capacity)
        private var writePos = 0
        private var filled = 0

        fun write(data: ByteArray, off: Int, len: Int) {
            var remaining = len
            var src = off
            while (remaining > 0) {
                val chunk = minOf(remaining, capacity - writePos)
                System.arraycopy(data, src, buffer, writePos, chunk)
                writePos = (writePos + chunk) % capacity
                if (filled < capacity) {
                    filled = minOf(capacity, filled + chunk)
                }
                remaining -= chunk
                src += chunk
            }
        }

        fun toByteArray(): ByteArray {
            val out = ByteArray(filled)
            val start = (writePos - filled + capacity) % capacity
            if (start + filled <= capacity) {
                System.arraycopy(buffer, start, out, 0, filled)
            } else {
                val firstLen = capacity - start
                System.arraycopy(buffer, start, out, 0, firstLen)
                System.arraycopy(buffer, 0, out, firstLen, filled - firstLen)
            }
            return out
        }
    }
}

fun main() = kotlinx.coroutines.runBlocking {
    println("Starting audio recording test...")
    println("Make sure your microphone is properly connected and has the necessary permissions.")

    try {
        println("Will record for 5 seconds. Speak into your microphone...")
        val wav = InMemoryOpusRecorder.recordPcm(seconds = 5)
        println("Converting to Opus format...")
        val oggOpus = InMemoryOpusRecorder.wavToOpusOgg(wav)
        val opusFile = File("capture.ogg")
        opusFile.writeBytes(oggOpus)
        println("Successfully saved ${oggOpus.size} bytes of Opus audio to ${opusFile.absolutePath}")
    } catch (e: Exception) {
        println("\nERROR: ${e.message}")
        exitProcess(1)
    }
}

/** Encode the given WAV bytes to Ogg/Opus and return the compressed bytes. */
fun InMemoryOpusRecorder.wavToOpusOgg(
    wavBytes: ByteArray,
    bitRate: Int = 64_000,
    sampleRate: Int = 48_000,
    channels: Int = 2
): ByteArray {
    val inFile = Files.createTempFile("jave-input", ".wav").toFile()
    val outFile = Files.createTempFile("jave-output", ".ogg").toFile()
    inFile.writeBytes(wavBytes)

    val audioAttr = AudioAttributes().apply {
        setCodec("libopus")
        setBitRate(bitRate)
        setSamplingRate(sampleRate)
        setChannels(channels)
    }

    val encAttr = EncodingAttributes().apply {
        setOutputFormat("ogg")
        setAudioAttributes(audioAttr)
    }

    Encoder().encode(MultimediaObject(inFile), outFile, encAttr)

    val encoded = outFile.readBytes()
    inFile.delete(); outFile.delete()
    return encoded
}

