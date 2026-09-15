package ru.souz.backend.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES/GCM/NoPadding at-rest encryption for a small secret (a bot token, etc), framed as
 * `<prefix><base64 iv>:<base64 ciphertext>`. Shared by per-channel token-crypto wrappers
 * (e.g. Telegram's, VK's) so the AES/GCM logic itself isn't duplicated per channel.
 */
class EncryptedPayloadCipher(
    private val payloadPrefix: String,
    rawBase64Key: String,
) {
    private val keyBytes = try {
        Base64.getDecoder().decode(rawBase64Key)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Token encryption key must be valid base64.")
    }.also { decoded ->
        require(decoded.size == KEY_SIZE_BYTES) {
            "Token encryption key must decode to $KEY_SIZE_BYTES bytes."
        }
    }
    private val secretKey = SecretKeySpec(keyBytes, "AES")

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_SIZE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return buildString {
            append(payloadPrefix)
            append(Base64.getEncoder().encodeToString(iv))
            append(':')
            append(Base64.getEncoder().encodeToString(cipherText))
        }
    }

    /** Throws on a payload that doesn't carry [payloadPrefix] — no plaintext passthrough. */
    fun decrypt(payload: String): String {
        require(isEncrypted(payload)) { "Malformed encrypted payload." }
        val parts = payload.removePrefix(payloadPrefix).split(':')
        require(parts.size == 2) { "Malformed encrypted payload." }
        val iv = Base64.getDecoder().decode(parts[0])
        val cipherText = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(payloadPrefix)

    private companion object {
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_SIZE_BYTES = 32
        const val IV_SIZE_BYTES = 12
        val secureRandom = SecureRandom()
    }
}
