package giga

import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.instance
import ru.souz.giga.GigaVoiceAPI
import java.io.File
import kotlin.test.assertTrue
import ru.souz.audio.InMemoryOpusRecorder
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
            val wavBytes = File("Generated.wav").readBytes()
            val oggBytes = InMemoryOpusRecorder.wavToOpusOgg(wavBytes)
            val text = api.recognize(oggBytes).result.joinToString("\n")
            assertTrue(text.contains("hello", ignoreCase = true))
        } finally {
            api.clear()
        }
    }
}
