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
    fun `overlapping waits and repeated state updates preserve processing and option time`() {
        var seconds = 0L
        BackendMetrics { TimeUnit.SECONDS.toNanos(seconds) }.use { metrics ->
            val timing = metrics.executions
            val execution = AgentExecution(
                UUID.randomUUID(), "user", UUID.randomUUID(), null, null, AgentExecutionStatus.QUEUED,
                null, null, null, null, Instant.EPOCH, null, false, null, null, null, emptyMap(),
            )
            fun transition(status: AgentExecutionStatus, at: Long) {
                seconds = at
                timing.committed(execution.copy(status = status))
            }
            transition(AgentExecutionStatus.QUEUED, 0)
            transition(AgentExecutionStatus.RUNNING, 10)
            seconds = 15
            val first = timing.beginClientWait(execution.id)
            seconds = 20
            val second = timing.beginClientWait(execution.id)
            seconds = 25
            first.close()
            seconds = 30
            second.close()
            transition(AgentExecutionStatus.WAITING_OPTION, 35)
            seconds = 40
            timing.beginClientWait(execution.id).use { seconds = 45 }
            transition(AgentExecutionStatus.WAITING_OPTION, 50)
            transition(AgentExecutionStatus.RUNNING, 65)
            seconds = 70
            timing.committed(execution.copy(status = AgentExecutionStatus.COMPLETED), AgentExecutionStatus.RUNNING)

            assertEquals(15.0, metrics.registry.get("souz.execution.duration").timer().totalTime(TimeUnit.SECONDS))
            assertEquals(30.0, metrics.registry.get("souz.execution.wait.duration").tag("reason", "user_option").timer().totalTime(TimeUnit.SECONDS))
            assertEquals(25.0, metrics.registry.get("souz.execution.wait.duration").tag("reason", "client_tool").timer().totalTime(TimeUnit.SECONDS))
            assertEquals(3L, metrics.registry.get("souz.execution.wait.duration").tag("reason", "client_tool").timer().count())
        }
    }
}
