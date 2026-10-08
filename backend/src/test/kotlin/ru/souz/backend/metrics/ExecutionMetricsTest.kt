package ru.souz.backend.metrics

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus

class ExecutionMetricsTest {
    @Test
    fun `processing excludes queued time option continuations and overlapping client waits`() {
        var seconds = 0L
        BackendMetrics { TimeUnit.SECONDS.toNanos(seconds) }.use { meters ->
            val timing = meters.executions
            val queued = AgentExecution(UUID.randomUUID(), "user", UUID.randomUUID(), null, null,
                AgentExecutionStatus.QUEUED, null, null, null, null, Instant.EPOCH, null, false, null, null, null, emptyMap())
            timing.committed(queued)
            seconds = 10
            timing.committed(queued.copy(status = AgentExecutionStatus.RUNNING), AgentExecutionStatus.QUEUED)
            seconds = 20
            val first = timing.beginClientWait(queued.id)
            seconds = 25
            val second = timing.beginClientWait(queued.id)
            seconds = 30
            first.close()
            seconds = 40
            second.close()
            seconds = 45
            timing.committed(queued.copy(status = AgentExecutionStatus.WAITING_OPTION), AgentExecutionStatus.RUNNING)
            seconds = 60
            val overlappingOption = timing.beginClientWait(queued.id)
            seconds = 70
            overlappingOption.close()
            seconds = 75
            timing.committed(queued.copy(status = AgentExecutionStatus.WAITING_OPTION)) // Idempotent request replay.
            seconds = 105
            timing.committed(queued.copy(status = AgentExecutionStatus.RUNNING), AgentExecutionStatus.WAITING_OPTION)
            seconds = 115
            timing.committed(queued.copy(status = AgentExecutionStatus.COMPLETED), AgentExecutionStatus.RUNNING)
            assertEquals(25.0, meters.registry.get("souz.execution.duration").timer().totalTime(TimeUnit.SECONDS))
            assertEquals(60.0, meters.registry.get("souz.execution.wait.duration").tag("reason", "user_option").timer().totalTime(TimeUnit.SECONDS))
            assertEquals(35.0, meters.registry.get("souz.execution.wait.duration").tag("reason", "client_tool").timer().totalTime(TimeUnit.SECONDS))
        }
    }
}
