package com.dumch.audio

import io.github.givimad.whisper.WhisperModel
import io.github.givimad.whisper.WhisperContext
import io.github.givimad.whisper.params.WhisperFullParams
import io.github.givimad.whisper.params.WhisperSamplingStrategy
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Wrapper around the whisper-jni library for simple speech recognition.
 */
object WhisperJniRecognizer {
    private val modelPath: String = System.getenv("WHISPER_MODEL_PATH") ?: "models/ggml-small.bin"
    private val model: WhisperModel by lazy { WhisperModel(File(modelPath).absolutePath) }

    fun recognize(wavBytes: ByteArray): String {
        val ctx: WhisperContext = model.createContext()
        val params = WhisperFullParams(WhisperSamplingStrategy.GREEDY).apply {
            setLanguage("ru")
            setTranslate(false)
            val grammarStream = javaClass.classLoader.getResourceAsStream("grammar/ru.gbnf")
            if (grammarStream != null) {
                setGrammar(grammarStream.bufferedReader().use { it.readText() })
            }
        }
        val tmp = File.createTempFile("rec", ".wav")
        tmp.writeBytes(wavBytes)
        ctx.full(params, tmp.absolutePath)
        tmp.delete()
        val builder = StringBuilder()
        val segments = ctx.getNumSegments()
        for (i in 0 until segments) {
            builder.append(ctx.getSegmentText(i))
        }
        return builder.toString().trim()
    }
}

fun main() = runBlocking {
    println("Starting whisper-jni recognition test...")
    println("Recording 5 seconds of audio. Speak in Russian now.")
    val wav = InMemoryOpusRecorder.recordPcm(seconds = 5)
    val text = WhisperJniRecognizer.recognize(wav)
    println("Recognized text: $text")
}
