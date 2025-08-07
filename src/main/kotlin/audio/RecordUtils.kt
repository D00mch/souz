package com.dumch.audio

import java.io.*
import java.nio.file.Files
import javax.sound.sampled.*
import kotlin.system.exitProcess
import kotlinx.coroutines.*
import ws.schild.jave.*
import ws.schild.jave.encode.AudioAttributes
import ws.schild.jave.encode.EncodingAttributes

/**
 * Simple utility that records the microphone, encodes to Ogg/Opus and returns the result as ByteArray.
 */
object InMemoryOpusRecorder {

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
            println("ERROR: Line not supported for format: $fmt")
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
            println("Error while closing audio line: ${e.message}")
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

    /** Encode the given WAV bytes to Ogg/Opus and return the compressed bytes. */
    fun wavToOpusOgg(wavBytes: ByteArray,
                     bitRate: Int = 64_000,
                     sampleRate: Int = 48_000,
                     channels: Int = 2): ByteArray {

        /* JAVE2 works on java.io.File – we therefore use tmp files but keep all
           data in RAM visible to the caller. */
        val inFile  = Files.createTempFile("jave-input",  ".wav").toFile()
        val outFile = Files.createTempFile("jave-output", ".ogg").toFile()
        inFile.writeBytes(wavBytes)

        val audioAttr = AudioAttributes().apply {
            setCodec("libopus")                // Opus encoder in FFmpeg
            setBitRate(bitRate)
            setSamplingRate(sampleRate)
            setChannels(channels)
        }

        val encAttr = EncodingAttributes().apply {
            setOutputFormat("ogg")                   // container
            setAudioAttributes(audioAttr)
        }

        Encoder().encode(MultimediaObject(inFile), outFile, encAttr)   // synchronous

        val encoded = outFile.readBytes()
        inFile.delete(); outFile.delete()
        return encoded
    }
}

fun main() = runBlocking {
    println("Starting audio recording test...")
    println("Make sure your microphone is properly connected and has the necessary permissions.")

    try {
        println("Will record for 5 seconds. Speak into your microphone...")
        // Record audio
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
