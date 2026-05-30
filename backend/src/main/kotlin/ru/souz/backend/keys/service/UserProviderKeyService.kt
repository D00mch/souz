package ru.souz.backend.keys.service

import java.time.Instant
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.keys.model.UserProviderKeyView
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.db.AesGcmSecretCodec
import ru.souz.llms.LlmProvider

class UserProviderKeyService(
    private val repository: UserProviderKeyRepository,
    private val masterKey: String,
) {
    suspend fun list(userId: String): List<UserProviderKeyView> =
        repository.list(userId)
            .sortedBy { it.provider.name }
            .map { key ->
                UserProviderKeyView(
                    provider = key.provider,
                    configured = true,
                    keyHint = key.keyHint,
                    updatedAt = key.updatedAt,
                )
            }

    suspend fun put(
        userId: String,
        provider: LlmProvider,
        apiKey: String,
    ): UserProviderKeyView {
        val now = Instant.now()
        val existing = repository.get(userId, provider)
        val key = repository.save(
            UserProviderKey(
                userId = userId,
                provider = provider,
                encryptedApiKey = AesGcmSecretCodec.encrypt(masterKey = masterKey, plainText = apiKey),
                keyHint = keyHint(apiKey),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
        return UserProviderKeyView(
            provider = key.provider,
            configured = true,
            keyHint = key.keyHint,
            updatedAt = key.updatedAt,
        )
    }

    suspend fun delete(
        userId: String,
        provider: LlmProvider,
    ): Boolean = repository.delete(userId, provider)

    suspend fun decrypt(
        userId: String,
        provider: LlmProvider,
    ): String? = repository.get(userId, provider)
        ?.encryptedApiKey
        ?.let { AesGcmSecretCodec.decrypt(masterKey = masterKey, payload = it) }

    suspend fun hasUserManagedKey(
        userId: String,
        provider: LlmProvider,
    ): Boolean = repository.get(userId, provider) != null

    private fun keyHint(apiKey: String): String =
        if (apiKey.length <= 4) {
            "..." + apiKey
        } else {
            "..." + apiKey.takeLast(4)
        }
}
