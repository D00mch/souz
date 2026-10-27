package ru.souz.backend.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import javax.sql.DataSource

/** Imported-history queue state read from PostgreSQL at scrape, plus this instance's exhausted fragments. */
internal class HistoryMemoryMetrics(registry: MeterRegistry) {
    private var dataSource: DataSource? = null
    @Volatile private var counts: Map<String, Long> = emptyMap()
    private val failures = registry.counter("souz.history.memory.failures")

    init {
        listOf("waiting", "processing", "retrying").forEach { state ->
            Gauge.builder("souz.history.memory.fragments", this) { (it.counts[state] ?: 0L).toDouble() }
                .tag("state", state).register(registry)
        }
    }

    fun bindDatabase(source: DataSource) { dataSource = source }

    fun failed() = failures.increment()

    fun prepareScrape() {
        val source = dataSource ?: return
        // A crashed worker's claim stays processing until another worker reclaims it.
        counts = source.connection.use { connection ->
            connection.prepareStatement(
                "select case when lease_token is not null then 'processing' when attempts > 0 then 'retrying' else 'waiting' end, " +
                    "count(*) from history_memory_fragments where completed_at is null group by 1"
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) }
                }
            }
        }
    }
}
