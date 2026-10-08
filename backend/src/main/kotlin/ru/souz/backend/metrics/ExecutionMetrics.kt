package ru.souz.backend.metrics

import io.micrometer.core.instrument.Gauge
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.isActive

/** Timing samples only; repository CAS/commits remain the authority for lifecycle changes. */
internal class ExecutionMetrics(private val meters: BackendMetrics, private val nanoTime: () -> Long = System::nanoTime) {
    private data class Sample(val state: AgentExecutionStatus, val since: Long, val processing: Long = 0, val clientWaits: Int = 0)
    private val samples = ConcurrentHashMap<UUID, Sample>()
    private var dataSource: javax.sql.DataSource? = null

    fun bindDatabase(source: javax.sql.DataSource) { dataSource = source }

    private val activeCounts = ConcurrentHashMap<AgentExecutionStatus, Long>()

    // Active state is shared database state; timing samples never define ownership/load.
    private fun activeCount(state: AgentExecutionStatus): Double = if (dataSource != null) {
        activeCounts[state]?.toDouble() ?: 0.0
    } else samples.values.count { it.state == state }.toDouble()

    fun prepareScrape() {
        val source = dataSource ?: return
        source.connection.use { connection ->
            val counts = connection.prepareStatement("select status, count(*) from agent_executions where status in ('queued', 'running', 'waiting_option', 'cancelling') group by status").use { statement ->
                statement.executeQuery().use { rows -> buildMap<String, Long> { while (rows.next()) put(rows.getString(1), rows.getLong(2)) } }
            }
            AgentExecutionStatus.entries.filter { it.isActive() }.forEach { activeCounts[it] = counts[it.value] ?: 0 }
            val ids = samples.keys.toTypedArray()
            if (ids.isEmpty()) return
            val array = connection.createArrayOf("uuid", ids)
            try {
                // Another replica may finish an option continuation. Delay pruning so a scrape
                // racing our own commit cannot consume its still-pending timing observation.
                connection.prepareStatement("select id from agent_executions where id = any (?) and finished_at < now() - interval '1 minute' and status in ('completed', 'failed', 'cancelled')").use { statement ->
                    statement.setArray(1, array)
                    statement.executeQuery().use { rows -> while (rows.next()) samples.remove(rows.getObject(1, UUID::class.java)) }
                }
            } finally { array.free() }
        }
    }

    init {
        AgentExecutionStatus.entries.filter { it.isActive() }.forEach { state ->
            Gauge.builder("souz.executions.active", this) { it.activeCount(state) }
                .tag("state", state.value).register(meters.registry)
        }
    }

    fun committed(execution: AgentExecution, previous: AgentExecutionStatus? = null) {
        if (!execution.status.isActive()) {
            // Only a successful active -> terminal write increments the counter.
            if (previous?.isActive() == true) meters.terminal(execution.status)
            samples.remove(execution.id)?.let { sample ->
                val now = nanoTime()
                endOptionWait(sample, now)
                meters.timer("souz.execution.duration").record(processingAt(sample, now), TimeUnit.NANOSECONDS)
            }
            return
        }
        samples.compute(execution.id) { _, sample ->
            val now = nanoTime()
            if (sample?.state == AgentExecutionStatus.WAITING_OPTION && execution.status != sample.state) endOptionWait(sample, now)
            Sample(execution.status, now, sample?.let { processingAt(it, now) } ?: 0, sample?.clientWaits ?: 0)
        }
    }

    fun beginClientWait(executionId: UUID): AutoCloseable {
        val started = nanoTime()
        samples.computeIfPresent(executionId) { _, sample ->
            sample.copy(since = started, processing = processingAt(sample, started), clientWaits = sample.clientWaits + 1)
        }
        return AutoCloseable {
            val ended = nanoTime()
            meters.timer("souz.execution.wait.duration", "reason", "client_tool").record(ended - started, TimeUnit.NANOSECONDS)
            samples.computeIfPresent(executionId) { _, sample ->
                sample.copy(since = ended, clientWaits = (sample.clientWaits - 1).coerceAtLeast(0))
            }
        }
    }

    private fun processingAt(sample: Sample, now: Long): Long = sample.processing +
        if (sample.state in PROCESSING_STATES && sample.clientWaits == 0) (now - sample.since).coerceAtLeast(0) else 0

    private fun endOptionWait(sample: Sample, now: Long) {
        if (sample.state == AgentExecutionStatus.WAITING_OPTION) {
            meters.timer("souz.execution.wait.duration", "reason", "user_option").record(now - sample.since, TimeUnit.NANOSECONDS)
        }
    }

    private companion object {
        val PROCESSING_STATES = setOf(AgentExecutionStatus.RUNNING, AgentExecutionStatus.CANCELLING)
    }
}
