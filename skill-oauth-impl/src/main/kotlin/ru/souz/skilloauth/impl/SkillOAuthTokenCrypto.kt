package ru.souz.skilloauth.impl

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM encryption for stored OAuth tokens, mirroring
 * `ru.souz.backend.telegram.TelegramBotTokenCrypto`. Uses a dedicated key so a leak of
 * the Telegram bot-token key does not also compromise skill OAuth tokens.
 */
class SkillOAuthTokenCrypto(
    rawBase64Key: String,
) {
    private val keyBytes = try {
        Base64.getDecoder().decode(rawBase64Key)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Skill OAuth token encryption key must be valid base64.")
    }.also { decoded ->
        require(decoded.size == KEY_SIZE_BYTES) {
            "Skill OAuth token encryption key must decode to $KEY_SIZE_BYTES bytes."
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
            append(PAYLOAD_PREFIX)
            append(Base64.getEncoder().encodeToString(iv))
            append(':')
            append(Base64.getEncoder().encodeToString(cipherText))
        }
    }

    fun decrypt(payload: String): String {
        require(isEncrypted(payload)) { "Payload is not an encrypted skill OAuth token." }
        val parts = payload.removePrefix(PAYLOAD_PREFIX).split(':')
        require(parts.size == 2) { "Malformed skill OAuth token payload." }
        val iv = Base64.getDecoder().decode(parts[0])
        val cipherText = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(cipherText).toString(Charsets.UTF_8)
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PAYLOAD_PREFIX)

    companion object {
        private const val PAYLOAD_PREFIX = "oauthenc:v1:"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BYTES = 32
        private const val IV_SIZE_BYTES = 12
        private val secureRandom = SecureRandom()
    }
}
