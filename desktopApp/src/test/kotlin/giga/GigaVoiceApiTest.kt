package giga

import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.instance
import ru.souz.llms.giga.GigaVoiceAPI
import java.io.File
import kotlin.test.assertTrue
import ru.souz.di.mainDiModule
import kotlin.test.Ignore


class GigaVoiceApiTest {

    private val di = DI.invoke { import(mainDiModule) }
    private val api: GigaVoiceAPI by di.instance()

    @Ignore
    fun test1() = runBlocking {
        val key = System.getenv("VOICE_KEY")
        if (key.isNullOrBlank()) {
            println("VOICE_KEY not set; skipping real API test")
            return@runBlocking
        }

        try {
            val audio = api.synthesize("<speak>Hello</speak>")
            assertTrue(audio.isNotEmpty())
            val vaw = File("Generated.wav")
            vaw.writeBytes(audio)
        } finally {
            api.clear()
        }
    }

    fun recognizeTest() = runBlocking {
        val key = System.getenv("VOICE_KEY")
        if (key.isNullOrBlank()) {
            println("VOICE_KEY not set; skipping real API test")
            return@runBlocking
        }

        try {
            val pcmBytes = File("capture2.pcm").readBytes()
            val text = api.recognize(pcmBytes).result.joinToString("\n")
            assertTrue(text.contains("hello", ignoreCase = true))
        } finally {
            api.clear()
        }
    }
}
