package giga

import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaVoiceAPI
import java.io.File
import kotlin.test.assertTrue

suspend fun main () {
    val key = System.getenv("VOICE_KEY")
    if (key.isNullOrBlank()) {
        println("VOICE_KEY not set; skipping real API test")
        return
    }

    val api = GigaVoiceAPI(GigaAuth)
    try {
        val audio = api.synthesize("<speak>Hello</speak>")
        assertTrue(audio.isNotEmpty())
        val vaw = File("Generated.wav")
        vaw.writeBytes(audio)
    } finally {
        api.clear()
    }
}
