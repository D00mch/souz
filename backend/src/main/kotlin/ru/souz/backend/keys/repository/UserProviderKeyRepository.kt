package ru.souz.backend.keys.repository

import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.llms.LlmProvider

interface UserProviderKeyRepository {
    suspend fun get(
        userId: String,
        provider: LlmProvider,
    ): UserProviderKey?

    suspend fun list(userId: String): List<UserProviderKey>

    suspend fun save(key: UserProviderKey): UserProviderKey

    suspend fun delete(
        userId: String,
        provider: LlmProvider,
    ): Boolean
}
