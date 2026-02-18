package ru.souz.audio

import ru.souz.keys.HotkeyListener
import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import ws.schild.jave.Encoder
import ws.schild.jave.MultimediaObject
import ws.schild.jave.encode.AudioAttributes
import ws.schild.jave.encode.EncodingAttributes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

class ActiveSoundRecorderImpl(
    sampleRate: Float = 16_000f,
    sampleSizeBits: Int = 16,
    channels: Int = 1,
    frameMillis: Int = 20,
    private val lineBufferBytes: Int = 16384,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : ActiveSoundRecorder {

    private val prepared = AtomicBoolean(false)

    private val format = AudioFormat(sampleRate, sampleSizeBits, channels, true, false)
    private val bytesPerSample = sampleSizeBits / 8
    private val samplesPerFrame = (sampleRate * frameMillis / 1000f).toInt()
    private val frameBytes = samplesPerFrame * bytesPerSample * channels

    private val channelLock = ReentrantLock()

    private var captureJob: Job? = null
    private var line: TargetDataLine? = null

    // Active recording sink (null when not recording)
    private val activeChannelRef = AtomicReference<Channel<ByteArray>?>(null)

    override fun prepare() {
        if (prepared.get()) return
        channelLock.withLock {
            if (prepared.get()) return
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val localLine = AudioSystem.getLine(info) as TargetDataLine
            localLine.open(format, maxOf(lineBufferBytes, frameBytes * 8))
            line = localLine

            captureJob = scope.launch {
                val buf = ByteArray(frameBytes)
                var filled = 0
                while (localLine.isOpen) {
                    val r = localLine.read(buf, filled, buf.size - filled)
                    if (r <= 0) continue
                    filled += r
                    if (filled == buf.size) {
                        val frame = buf.copyOf()
                        filled = 0
                        channelLock.withLock {
                            activeChannelRef.get()?.trySend(frame)
                        }
                    }
                }
            }
            prepared.set(true)
        }
    }

    override fun startRecording() {
        if (!prepared.get()) prepare() // idempotent
        val localLine = line ?: throw IllegalStateException("Recorder is not prepared")
        val ch = Channel<ByteArray>(capacity = 3072)

        channelLock.withLock {
            activeChannelRef.getAndSet(ch)?.close()
        }
        localLine.flush()
        localLine.start()
    }

    override suspend fun stopRecording(): ByteArray {
        val ch = channelLock.withLock { activeChannelRef.getAndSet(null) } ?: return ByteArray(0)
        line?.stop()
        line?.flush()
        ch.close() // stop producers to this channel

        val chunks = ArrayList<ByteArray>(256)
        for (f in ch) chunks.add(f) // drains remaining frames

        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, pos, c.size)
            pos += c.size
        }
        return out
    }

    /** Optional: call when app exits to release the mic. */
    suspend fun close() {
        activeChannelRef.getAndSet(null)?.close()
        line?.apply {
            stop()
            flush()
            close()
        }
        captureJob?.cancelAndJoin()
        scope.cancel()
    }
}

interface ActiveSoundRecorder {

    /**
     * Open the microphone line and create internal capture resources.
     * The line is not started until [startRecording] is called.
     */
    fun prepare()

    /** Begin writing live audio data to the main stream. */
    fun startRecording()

    /** Stop capturing and return the recorded audio. */
    suspend fun stopRecording(): ByteArray
}

fun rawToOpusOgg(
    rawData: ByteArray,
    sampleRate: Float = 16_000f,
    sampleSizeInBits: Int = 16,
    channels: Int = 1,
    bitRate: Int = 64_000
): ByteArray {
    // First convert raw data to WAV format
    val wavBytes = rawToWav(rawData, sampleRate, sampleSizeInBits, channels)

    // Then convert WAV to Opus/Ogg
    val inFile = Files.createTempFile("jave-input", ".wav").toFile()
    val outFile = Files.createTempFile("jave-output", ".ogg").toFile()
    inFile.writeBytes(wavBytes)

    val audioAttr = AudioAttributes().apply {
        setCodec("libopus")
        setBitRate(bitRate)
        setSamplingRate(sampleRate.toInt())
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

private fun rawToWav(
    rawData: ByteArray,
    sampleRate: Float,
    sampleSizeInBits: Int,
    channels: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val byteRate = (sampleRate * sampleSizeInBits * channels / 8).toInt()
    val blockAlign = (sampleSizeInBits * channels / 8).toShort()
    val bitsPerSample = sampleSizeInBits.toShort()
    
    // Write WAV header
    out.write("RIFF".toByteArray())
    writeInt(out, 36 + rawData.size) // ChunkSize
    out.write("WAVE".toByteArray())
    
    // Write format subchunk
    out.write("fmt ".toByteArray())
    writeInt(out, 16) // Subchunk1Size
    writeShort(out, 1) // AudioFormat (1 = PCM)
    writeShort(out, channels)
    writeInt(out, sampleRate.toInt())
    writeInt(out, byteRate)
    writeShort(out, blockAlign.toInt())
    writeShort(out, bitsPerSample.toInt())
    
    // Write data subchunk
    out.write("data".toByteArray())
    writeInt(out, rawData.size) // Subchunk2Size
    out.write(rawData)
    
    return out.toByteArray()
}

private fun writeShort(stream: OutputStream, value: Int) {
    val buffer = ByteArray(2)
    ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    stream.write(buffer)
}

private fun writeInt(stream: OutputStream, value: Int) {
    val buffer = ByteArray(4)
    ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    stream.write(buffer)
}

suspend fun main() {
    val audioRecorder = InMemoryAudioRecorder(
        coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    )
    val hotkeyListener = HotkeyListener(
        onPressed = { pressed ->
            println(if (pressed) "onStart" else "onStop")
            when {
                pressed -> audioRecorder.start()
                else -> audioRecorder.stop()
            }
        },
        onDoubleClick = { println("double click") }
    )

    var i = 0
    try {
        GlobalScreen.registerNativeHook()
        GlobalScreen.addNativeKeyListener(hotkeyListener)
        audioRecorder.audioFlow.collect { audioData: ByteArray ->
            println("Recorded audio: ${audioData.size} bytes")
            val outputFile = File("recording${i++}.wav")
            val oggBytes = rawToOpusOgg(
                rawData = audioData,
                sampleRate = 16_000f,
                channels = 1,
                sampleSizeInBits = 16
            )
            FileOutputStream(outputFile).use {
                it.write(oggBytes)
            }
        }
    } catch (e: NativeHookException) {
        System.err.println("Failed to register native hook: ${e.message}")
        exitProcess(1)
    }

    Thread.currentThread().join()
}
