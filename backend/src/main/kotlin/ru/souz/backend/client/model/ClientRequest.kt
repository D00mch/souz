package ru.souz.backend.client.model

import java.time.Instant
import java.util.UUID

data class ClientRequest(
    val chatId: UUID,
    val requestId: String,
    val kind: String,
    val threadId: UUID?,
    val payloadHash: String,
    val ackJson: String,
    val receivedAt: Instant,
)
