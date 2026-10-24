package ru.souz.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryRulesTest {
    @Test
    fun `redaction removes obvious secrets and private paths`() {
        val raw = """
            Authorization: Bearer sk-secret-1234567890
            OPENAI_API_KEY=sk-prod-abcdef1234567890
            /Users/duxx/Secrets/notes.txt
            /home/alice/project/.env
            ~/.local/state/souz/private/key.txt
            C:\Users\Alice\Secrets\notes.txt
            user@example.com
            dGhpcy1sb29rcy1saWtlLWEtc2VjcmV0LXRva2VuLWFuZC1zaG91bGQtYmUtcmVkYWN0ZWQ=
        """.trimIndent()

        val redacted = MemorySanitizer.redact(raw)

        assertFalse(redacted.contains("sk-secret-1234567890"))
        assertFalse(redacted.contains("sk-prod-abcdef1234567890"))
        assertFalse(redacted.contains("/Users/duxx/Secrets/notes.txt"))
        assertFalse(redacted.contains("/home/alice/project/.env"))
        assertFalse(redacted.contains("~/.local/state/souz/private/key.txt"))
        assertFalse(redacted.contains("""C:\Users\Alice\Secrets\notes.txt"""))
        assertFalse(redacted.contains("user@example.com"))
        assertFalse(redacted.contains("dGhpcy1sb29rcy1saWtl"))
        assertTrue(redacted.contains("[redacted-auth]"))
        assertTrue(redacted.contains("[redacted-secret]"))
        assertTrue(redacted.contains("[redacted-path]"))
        assertTrue(redacted.contains("[redacted-email]"))
    }

    @Test
    fun `explicit remember parser gives negative priority`() {
        assertEquals(ExplicitMemoryIntent.DO_NOT_CAPTURE_THIS_TURN, parseExplicitMemoryIntent("не запоминай это"))
        assertEquals(ExplicitMemoryIntent.DO_NOT_CAPTURE_THIS_TURN, parseExplicitMemoryIntent("don't remember this"))
        assertEquals(ExplicitMemoryIntent.FORGET_EXISTING, parseExplicitMemoryIntent("forget this"))
        assertEquals(ExplicitMemoryIntent.FORGET_EXISTING, parseExplicitMemoryIntent("забудь это"))
        assertEquals(ExplicitMemoryIntent.REMEMBER_SIGNAL, parseExplicitMemoryIntent("запомни, что я предпочитаю Kotlin"))
        assertEquals(ExplicitMemoryIntent.REMEMBER_SIGNAL, parseExplicitMemoryIntent("remember that I prefer Kotlin"))
        assertEquals(ExplicitMemoryIntent.REMEMBER_SIGNAL, parseExplicitMemoryIntent("don't forget that I prefer Kotlin"))
        assertEquals(ExplicitMemoryIntent.REMEMBER_SIGNAL, parseExplicitMemoryIntent("не забудь, что я предпочитаю Kotlin"))
        assertEquals(ExplicitMemoryIntent.DO_NOT_CAPTURE_THIS_TURN, parseExplicitMemoryIntent("remember that, but don't save this"))
        assertEquals(ExplicitMemoryIntent.NONE, parseExplicitMemoryIntent("Explain how an LSTM forget gate works"))
        assertEquals(ExplicitMemoryIntent.NONE, parseExplicitMemoryIntent("Расскажи про forgetting curve"))
        assertEquals(ExplicitMemoryIntent.NONE, parseExplicitMemoryIntent("Просто ответь на вопрос"))
    }

}
