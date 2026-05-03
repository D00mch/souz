package ru.souz.backend.keys.model

import java.time.Instant
import java.time.temporal.ChronoUnit
import ru.souz.llms.LlmProvider

data class UserProviderKey(
    val userId: String,
    val provider: LlmProvider,
    val encryptedApiKey: String,
    val keyHint: String,
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
    val updatedAt: Instant = createdAt,
)

data class UserProviderKeyView(
    val provider: LlmProvider,
    val configured: Boolean,
    val keyHint: String?,
    val updatedAt: Instant?,
)
