package ru.souz.backend.client

import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.PublicErrorPayload
import ru.souz.backend.events.model.ThreadFailedPayload
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.repository.AgentExecutionRepository

internal class ClientThreadRecoveryService(
    private val executionRepository: AgentExecutionRepository,
    private val eventService: AgentEventService,
    private val clock: Clock = Clock.systemUTC(),
    private val recoveryInterval: Duration = ClientThreadRuntimeRegistry.LEASE_REFRESH_INTERVAL,
) {
    private val logger = LoggerFactory.getLogger(ClientThreadRecoveryService::class.java)

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(recoveryInterval.toMillis())
            try {
                recover()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("Client thread recovery sweep failed: {}", error.message)
            }
        }
    }

    suspend fun recover() {
        val now = clock.instant()
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
