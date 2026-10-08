package ru.souz.backend.metrics

import io.micrometer.core.instrument.Gauge
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.isActive

/** Repository commits determine outcomes; these process-local samples only measure time. */
internal class ExecutionMetrics(private val meters: BackendMetrics, private val nanoTime: () -> Long) {
    private data class Sample(
        val status: AgentExecutionStatus,
        val since: Long,
        val processing: Long = 0,
        val clientWaits: Int = 0,
        val optionStarted: Long? = null,
    ) {
        fun processingAt(now: Long): Long = processing +
            if (clientWaits == 0 && (status == AgentExecutionStatus.RUNNING || status == AgentExecutionStatus.CANCELLING)) {
                (now - since).coerceAtLeast(0)
            } else 0
    }

    private val samples = ConcurrentHashMap<UUID, Sample>()
    private var dataSource: DataSource? = null
    @Volatile private var activeCounts: Map<String, Long> = emptyMap()
    private val processing = meters.registry.timer("souz.execution.duration")
    private val clientWaiting = meters.registry.timer("souz.execution.wait.duration", "reason", "client_tool")
    private val optionWaiting = meters.registry.timer("souz.execution.wait.duration", "reason", "user_option")

    init {
        AgentExecutionStatus.entries.filter { it.isActive() }.forEach { status ->
            Gauge.builder("souz.executions.active", this) { (it.activeCounts[status.value] ?: 0L).toDouble() }
                .tag("state", status.value).register(meters.registry)
        }
    }

    fun bindDatabase(source: DataSource) { dataSource = source }

    fun prepareScrape() {
        val source = dataSource ?: return
        source.connection.use { connection ->
            activeCounts = connection.prepareStatement(
                "select status, count(*) from agent_executions where status in ('queued', 'running', 'waiting_option', 'cancelling') group by status"
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) }
                }
            }
            if (samples.isEmpty()) return
            val ids = connection.createArrayOf("uuid", samples.keys.toTypedArray())
            try {
                // A different replica can finish a continuation. Keep a grace period for the
                // interval between our own transaction commit and its metrics callback.
                connection.prepareStatement(
                    "select id from agent_executions where id = any (?) and finished_at < now() - interval '1 minute' and status in ('completed', 'failed', 'cancelled')"
                ).use { statement ->
                    statement.setArray(1, ids)
                    statement.executeQuery().use { rows ->
                        while (rows.next()) samples.remove(rows.getObject(1, UUID::class.java))
                    }
                }
            } finally { ids.free() }
        }
    }

    fun committed(execution: AgentExecution, previous: AgentExecutionStatus? = null) {
        val now = nanoTime()
        if (!execution.status.isActive()) {
            if (previous?.isActive() == true) meters.terminal(execution.status)
            samples.remove(execution.id)?.let { sample ->
                finishOptionWait(sample, now)
                processing.record(sample.processingAt(now), TimeUnit.NANOSECONDS)
            }
            return
        }
        samples.compute(execution.id) { _, sample ->
            if (sample?.status == execution.status) return@compute sample
            if (sample != null) finishOptionWait(sample, now)
            Sample(
                execution.status, now, sample?.processingAt(now) ?: 0, sample?.clientWaits ?: 0,
                now.takeIf { execution.status == AgentExecutionStatus.WAITING_OPTION },
            )
        }
    }

    fun beginClientWait(executionId: UUID): AutoCloseable {
        val started = nanoTime()
        samples.computeIfPresent(executionId) { _, sample ->
            sample.copy(since = started, processing = sample.processingAt(started), clientWaits = sample.clientWaits + 1)
        }
        return AutoCloseable {
            val ended = nanoTime()
            clientWaiting.record((ended - started).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            samples.computeIfPresent(executionId) { _, sample ->
                sample.copy(since = ended, clientWaits = (sample.clientWaits - 1).coerceAtLeast(0))
            }
        }
    }

    private fun finishOptionWait(sample: Sample, now: Long) {
        sample.optionStarted?.let { optionWaiting.record((now - it).coerceAtLeast(0), TimeUnit.NANOSECONDS) }
    }
}
