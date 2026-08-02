package ru.souz.backend.client

import java.time.Instant
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PublicErrorPayload
import ru.souz.backend.events.model.ThreadFailedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.repository.AgentExecutionRepository

internal class ClientThreadRecoveryService(
    private val executionRepository: AgentExecutionRepository,
    private val eventService: AgentEventService,
) {
    suspend fun recover() {
        val now = Instant.now()
        (
            executionRepository.failInterruptedClientThreads(now) +
                executionRepository.findRecoveredClientThreadsMissingTerminalEvents()
            ).distinctBy { it.id }.forEach { execution ->
            eventService.appendDurable(
                userId = execution.userId,
                chatId = execution.chatId,
                executionId = execution.id,
                type = AgentEventType.THREAD_FAILED,
                payload = ThreadFailedPayload(
                    PublicErrorPayload(
                        code = "internal_error",
                        message = "The thread stopped because Souz restarted.",
                    )
                ),
            )
        }
    }
}
