package ru.souz.telemetry

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.souz.service.telemetry.TelemetryEvent
import ru.souz.service.telemetry.TelemetryEventType
import ru.souz.service.telemetry.TelemetryOutboxRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Files

class TelemetryOutboxRepositoryTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `enqueue retry and delete lifecycle works`() {
        val repo = TelemetryOutboxRepository(
            databasePath = Files.createTempDirectory("telemetry-outbox-test").resolve("telemetry.db"),
            objectMapper = objectMapper,
        )
        val event = TelemetryEvent(
            eventId = "event-1",
            type = TelemetryEventType.REQUEST_FINISHED.wireName,
            occurredAtMs = 1_000L,
            userId = "user-1",
            deviceId = "device-1",
            appSessionId = "app-session-1",
            payload = mapOf("status" to "success"),
        )

        repo.enqueue(listOf(event))

        assertEquals(1, repo.pendingCount())

        val queued = repo.loadReadyBatch(limit = 10, nowMs = 1_000L)
        assertEquals(1, queued.size)
        assertEquals(event, queued.single().event)

        repo.markFailed(
            rowIds = listOf(queued.single().rowId),
            nextAttemptAtMs = 2_000L,
            errorMessage = "temporary failure",
        )

        assertTrue(repo.loadReadyBatch(limit = 10, nowMs = 1_999L).isEmpty())

        val retried = repo.loadReadyBatch(limit = 10, nowMs = 2_000L)
        assertEquals(1, retried.size)
        assertEquals(1, retried.single().attemptCount)

        repo.delete(listOf(retried.single().rowId))

        assertEquals(0, repo.pendingCount())
    }
}
