package ru.souz.backend.telegram

import ru.souz.backend.crypto.EncryptedPayloadCipher

class TelegramBotTokenCrypto(
    rawBase64Key: String,
) {
    private val cipher = EncryptedPayloadCipher(PAYLOAD_PREFIX, rawBase64Key)

    fun encrypt(plainText: String): String = cipher.encrypt(plainText)

    /** Backward-compatible with pre-migration plaintext rows — migration-only, not a target state. */
    fun decrypt(payload: String): String =
        if (isEncrypted(payload)) cipher.decrypt(payload) else payload

    fun isEncrypted(value: String): Boolean = cipher.isEncrypted(value)

    private companion object {
        const val PAYLOAD_PREFIX = "tgenc:v1:"
    }
}
