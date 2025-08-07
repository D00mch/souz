package giga

import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaVoiceAPI
import com.dumch.audio.InMemoryOpusRecorder
import java.io.File
import kotlin.test.assertTrue

suspend fun main() {
    val key = System.getenv("VOICE_KEY")
    if (key.isNullOrBlank()) {
        println("VOICE_KEY not set; skipping real API test")
        return
    }

    val api = GigaVoiceAPI(GigaAuth)
    try {
        val wavBytes = File("Generated.wav").readBytes()
        val oggBytes = InMemoryOpusRecorder.wavToOpusOgg(wavBytes)
        val text = api.recognize(oggBytes)
        assertTrue(text.contains("hello", ignoreCase = true))
    } finally {
        api.clear()
    }
}
