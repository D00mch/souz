package giga

import ru.abledo.giga.GigaAuth
import ru.abledo.giga.GigaVoiceAPI
import ru.abledo.audio.InMemoryOpusRecorder
import java.io.File
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

suspend fun main() {
    val l = LoggerFactory.getLogger("GigaVoiceRecognizeTest")
    val key = System.getenv("VOICE_KEY")
    if (key.isNullOrBlank()) {
        l.info("VOICE_KEY not set; skipping real API test")
        return
    }

    val api = GigaVoiceAPI(GigaAuth)
    try {
        val wavBytes = File("Generated.wav").readBytes()
        val oggBytes = InMemoryOpusRecorder.wavToOpusOgg(wavBytes)
        val text = api.recognize(oggBytes).result.joinToString("\n")
        assertTrue(text.contains("hello", ignoreCase = true))
    } finally {
        api.clear()
    }
}
