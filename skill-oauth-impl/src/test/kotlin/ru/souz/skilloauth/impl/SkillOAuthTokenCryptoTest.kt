package ru.souz.skilloauth.impl

import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SkillOAuthTokenCryptoTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private val crypto = SkillOAuthTokenCrypto(key)

    @Test
    fun `encrypt then decrypt round-trips the plaintext`() {
        val plainText = "ya29.some-access-token-value"

        val encrypted = crypto.encrypt(plainText)

        assertTrue(crypto.isEncrypted(encrypted))
        assertNotEquals(plainText, encrypted)
        assertEquals(plainText, crypto.decrypt(encrypted))
    }

    @Test
    fun `each encryption uses a fresh random IV so ciphertexts differ`() {
        val plainText = "same-token"

        val first = crypto.encrypt(plainText)
        val second = crypto.encrypt(plainText)

        assertNotEquals(first, second)
        assertEquals(plainText, crypto.decrypt(first))
        assertEquals(plainText, crypto.decrypt(second))
    }

    @Test
    fun `isEncrypted is false for plain unprefixed values`() {
        assertFalse(crypto.isEncrypted("plain-value"))
    }

    @Test
    fun `rejects a key that does not decode to 32 bytes`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        val error = runCatching { SkillOAuthTokenCrypto(shortKey) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
