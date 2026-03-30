package ru.souz.service.audio

import java.io.*
import javax.sound.sampled.*
import kotlin.system.exitProcess
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

/**
 * Simple utility that records the microphone and returns WAV bytes.
 */
object InMemoryWavRecorder {
    private val l = LoggerFactory.getLogger(InMemoryWavRecorder::class.java)
    private var line: TargetDataLine? = null
    private var format: AudioFormat? = null
    private var rawOut: ByteArrayOutputStream? = null
    private var recordingJob: Job? = null

    fun startRecording(
        scope: CoroutineScope,
        sampleRate: Float = 44_100f,
        channels: Int = 1,
        sampleSizeBits: Int = 16
    ): Job {
        val fmt = AudioFormat(sampleRate, sampleSizeBits, channels, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, fmt)
        if (!AudioSystem.isLineSupported(info)) {
            l.error("Line not supported for format: $fmt")
            throw LineUnavailableException("Line not supported for format: $fmt")
        }

        val target = AudioSystem.getLine(info) as TargetDataLine
        target.open(fmt)
        target.start()

        line = target
        format = fmt
        rawOut = ByteArrayOutputStream()

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            while (isActive) {
                val read = target.read(buffer, 0, buffer.size)
                if (read > 0) {
                    rawOut?.write(buffer, 0, read)
                }
            }
        }

        return recordingJob as Job
    }

    suspend fun stopRecording(): ByteArray {
        delay(2_000)
        recordingJob?.cancelAndJoin()

        val target = line
        val fmt = format
        val bos = rawOut

        try {
            target?.stop()
            target?.close()
            target?.flush()
        } catch (e: Exception) {
            l.error("Error while closing audio line: ${e.message}")
        }

        line = null
        recordingJob = null

        val rawBytes = bos?.toByteArray() ?: ByteArray(0)
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
        startRecording(scope, sampleRate, channels, sampleSizeBits)
        delay(seconds * 1_000L)
        val data = stopRecording()
        scope.cancel()
        return data
    }

}

fun main() = runBlocking {
    val l = LoggerFactory.getLogger("RecordUtils")
    l.info("Starting audio recording test...")
    l.info("Make sure your microphone is properly connected and has the necessary permissions.")

    try {
        l.info("Will record for 5 seconds. Speak into your microphone...")
        // Record audio as WAV bytes
        val wav = InMemoryWavRecorder.recordPcm(seconds = 5)
        val wavFile = File("capture.wav")
        wavFile.writeBytes(wav)
        l.info("Successfully saved ${wav.size} bytes of WAV audio to ${wavFile.absolutePath}")
    } catch (e: Exception) {
        l.error("\nERROR: ${e.message}")
        exitProcess(1)
    }
}
