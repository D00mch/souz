package ru.souz.backend.client.repository

import java.util.UUID
import ru.souz.backend.client.model.ClientRequest

interface ClientRequestRepository {
    suspend fun create(request: ClientRequest): ClientRequest
    suspend fun get(chatId: UUID, requestId: String): ClientRequest?
}
