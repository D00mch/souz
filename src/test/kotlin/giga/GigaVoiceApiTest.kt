package giga

import com.dumch.giga.GigaAuth
import com.dumch.giga.GigaVoiceAPI
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GigaVoiceApiTest {
    @Test
    fun `synthesize small text`() = runBlocking {
        val key = System.getenv("VOICE_KEY")
        if (key.isNullOrBlank()) {
            println("VOICE_KEY not set; skipping real API test")
            return@runBlocking
        }

        val api = GigaVoiceAPI(GigaAuth)
        try {
            val audio = api.synthesize("<speak>Hello</speak>")
            assertTrue(audio.isNotEmpty())
            val header = audio.copyOfRange(0, 4).decodeToString()
            assertEquals("RIFF", header)
        } finally {
            api.clear()
        }
    }
}
