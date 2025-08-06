package com.dumch.audio

import java.io.*
import java.nio.file.Files
import javax.sound.sampled.*
import kotlin.system.exitProcess
import ws.schild.jave.*
import ws.schild.jave.encode.AudioAttributes
import ws.schild.jave.encode.EncodingAttributes

/** Simple utility that records the microphone, encodes to Ogg/Opus and returns the result as ByteArray. */
object InMemoryOpusRecorder {

    /** Record the microphone for the given number of seconds. */
    fun recordPcm(seconds: Int,
                  sampleRate: Float = 44_100f,  // Changed from 48kHz to 44.1kHz for better compatibility
                  channels: Int = 1,           // Changed to mono for simplicity
                  sampleSizeBits: Int = 16): ByteArray {

        // List available mixers for debugging
        println("Available mixers:")
        AudioSystem.getMixerInfo().forEach { 
            println("- ${it.name} (${it.description})")
        }

        val format = AudioFormat(sampleRate, sampleSizeBits, channels, /*signed =*/ true, /*bigEndian =*/ false)
        println("Trying to open audio format: $format")
        
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            println("ERROR: Line not supported for format: $format")
            println("Available target data lines:")
            AudioSystem.getTargetLineInfo(info).forEach { println("- $it") }
            throw LineUnavailableException("Line not supported for format: $format")
        }
        
        val line = AudioSystem.getLine(info) as TargetDataLine

        try {
            line.open(format)
            line.start()
            println("Successfully opened audio line")

            val rawOut = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            val endTime = System.currentTimeMillis() + seconds * 1_000L
            var totalBytesRead = 0L
            var iterations = 0

            println("Starting recording...")
            while (System.currentTimeMillis() < endTime) {
                val read = line.read(buffer, 0, buffer.size)
                if (read > 0) {
                    rawOut.write(buffer, 0, read)
                    totalBytesRead += read
                } else if (read < 0) {
                    println("Warning: Read $read bytes from audio line")
                }
                iterations++
            }
            println("Recording finished. Read $totalBytesRead bytes in $iterations iterations")

            if (totalBytesRead == 0L) {
                throw IllegalStateException("No audio data was captured. Check your microphone permissions and connections.")
            }

            // Wrap raw PCM in a WAV header
            val frames = rawOut.size() / format.frameSize
            val wavBOS = ByteArrayOutputStream()
            val ais = AudioInputStream(
                ByteArrayInputStream(rawOut.toByteArray()),
                format,
                frames.toLong()
            )
            
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavBOS)
            println("Generated WAV file with ${wavBOS.size()} bytes")
            return wavBOS.toByteArray()
        } finally {
            try {
                line.stop()
                line.close()
                line.flush()
            } catch (e: Exception) {
                println("Error while closing audio line: ${e.message}")
            }
        }
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

fun main() {
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
