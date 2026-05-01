package ru.souz.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AesGcmSecretCodecTest {
    @Test
    fun `codec encrypts and decrypts with explicit master key`() {
        val masterKey = "test-master-key"
        val plainText = "sk-secret-123456"

        val encrypted = AesGcmSecretCodec.encrypt(masterKey = masterKey, plainText = plainText)
        val decrypted = AesGcmSecretCodec.decrypt(masterKey = masterKey, payload = encrypted)

        assertNotEquals(plainText, encrypted)
        assertEquals(plainText, decrypted)
    }
}
