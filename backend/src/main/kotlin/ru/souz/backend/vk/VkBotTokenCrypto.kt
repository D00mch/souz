package ru.souz.backend.vk

import ru.souz.backend.crypto.EncryptedPayloadCipher

class VkBotTokenCrypto(
    rawBase64Key: String,
) {
    private val cipher = EncryptedPayloadCipher(PAYLOAD_PREFIX, rawBase64Key)

    fun encrypt(plainText: String): String = cipher.encrypt(plainText)

    /** No legacy plaintext data for VK — fails loudly on a malformed or unprefixed payload. */
    fun decrypt(payload: String): String = cipher.decrypt(payload)

    private companion object {
        const val PAYLOAD_PREFIX = "vkenc:v1:"
    }
}
