package ru.souz.backend.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.tool.ToolCategory

/** Backend-owned meters. IDs and payloads never become meter tags. */
class BackendMetrics(nanoTime: () -> Long = System::nanoTime) : AutoCloseable {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    internal val executions = ExecutionMetrics(this, nanoTime)
    private val connections = AtomicInteger()
    private val pendingTools = AtomicInteger()
    private val gc = JvmGcMetrics()
    private var clientToolNames: Set<String> = emptySet()

    internal fun registerClientTools(names: Set<String>) { clientToolNames = names }

    init {
        registry.config().meterFilter(object : io.micrometer.core.instrument.config.MeterFilter {
            override fun configure(id: io.micrometer.core.instrument.Meter.Id, config: io.micrometer.core.instrument.distribution.DistributionStatisticConfig): io.micrometer.core.instrument.distribution.DistributionStatisticConfig =
                if (id.name in setOf("jvm.gc.pause", "hikaricp.connections.acquire")) {
                    io.micrometer.core.instrument.distribution.DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .minimumExpectedValue(10_000_000.0)
                        .maximumExpectedValue(900_000_000_000.0)
                        .serviceLevelObjectives(*DURATION_BUCKETS.map { it.toNanos().toDouble() }.toDoubleArray())
                        .build().merge(config)
                } else config
        })
        listOf(ClassLoaderMetrics(), JvmMemoryMetrics(), JvmThreadMetrics(), ProcessorMetrics(), UptimeMetrics(), gc)
            .forEach { it.bindTo(registry) }
        Gauge.builder("souz.ws.connections.active", connections) { it.get().toDouble() }.register(registry)
        Gauge.builder("souz.pending.tool.calls", pendingTools) { it.get().toDouble() }.register(registry)
        listOf("completed", "failed", "cancelled").forEach { outcome ->
            counter("souz.executions", "outcome", outcome)
        }
        timer("souz.execution.duration")
        listOf("user_option", "client_tool").forEach { timer("souz.execution.wait.duration", "reason", it) }
        listOf("queue_overflow", "disconnect", "send_failure", "overtaken").forEach { dropped(it, 0.0) }
        val models = (LLMModel.entries.map { it.provider to it.alias } + EmbeddingsModel.entries.map { it.provider to it.alias })
            .groupBy({ it.first }, { it.second })
        LlmProvider.entries.forEach { provider ->
            (models[provider].orEmpty().distinct() + "other").forEach { model ->
                listOf("success", "error", "timeout", "cancelled").forEach { outcome ->
                    val tags = arrayOf("provider", provider.name.lowercase(), "model", model, "outcome", outcome)
                    counter("souz.llm.requests", *tags)
                    timer("souz.llm.request.duration", *tags)
                }
                listOf("input", "output").forEach {
                    counter("souz.llm.tokens", "provider", provider.name.lowercase(), "model", model, "direction", it)
                }
            }
        }
        (ToolCategory.entries.map { it.name.lowercase() } + "other").forEach { category ->
            listOf("success", "error", "timeout", "cancelled").forEach { outcome ->
                counter("souz.tool.calls", "category", category, "outcome", outcome)
                timer("souz.tool.duration", "category", category, "outcome", outcome)
            }
        }
    }

    internal fun timer(name: String, vararg tags: String): Timer = Timer.builder(name)
        .tags(*tags)
        .publishPercentileHistogram()
        .minimumExpectedValue(Duration.ofMillis(10))
        .maximumExpectedValue(Duration.ofMinutes(15))
        .serviceLevelObjectives(*DURATION_BUCKETS)
        .register(registry)

    private fun counter(name: String, vararg tags: String): Counter = Counter.builder(name).tags(*tags).register(registry)

    internal fun terminal(status: AgentExecutionStatus) = counter("souz.executions", "outcome", status.value).increment()

    internal fun llmAttempt(provider: LlmProvider, model: String, outcome: String, elapsedNanos: Long) {
        val tags = arrayOf("provider", provider.name.lowercase(), "model", modelLabel(provider, model), "outcome", outcome)
        counter("souz.llm.requests", *tags).increment()
        timer("souz.llm.request.duration", *tags).record(elapsedNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    internal fun llmUsage(provider: LlmProvider, model: String, usage: LLMResponse.Usage) {
        val tags = arrayOf("provider", provider.name.lowercase(), "model", modelLabel(provider, model))
        counter("souz.llm.tokens", *tags, "direction", "input").increment(usage.promptTokens.coerceAtLeast(0).toDouble())
        counter("souz.llm.tokens", *tags, "direction", "output").increment(usage.completionTokens.coerceAtLeast(0).toDouble())
    }

    internal fun toolStarted() { pendingTools.incrementAndGet() }
    internal fun toolFinished() { pendingTools.decrementAndGet() }
    internal fun socketOpened() { connections.incrementAndGet() }
    internal fun socketClosed() { connections.decrementAndGet() }
    internal fun dropped(reason: String, count: Double = 1.0) { counter("souz.ws.events.dropped", "reason", reason).increment(count) }

    internal val toolTelemetry = object : AgentTelemetry {
        override fun toolExecutionStarted(functionName: String) { if (functionName !in clientToolNames) toolStarted() }
        override fun toolExecutionFinished(functionName: String) { if (functionName !in clientToolNames) toolFinished() }
        override fun recordToolExecution(event: ru.souz.agent.spi.AgentToolExecutionEvent) {
            if (event.functionName in clientToolNames) return
            val category = ToolCategory.entries.firstOrNull { it.name == event.toolCategory }?.name?.lowercase() ?: "other"
            val outcome = when {
                event.errorType == "TimeoutCancellationException" -> "timeout"
                event.errorType?.contains("Cancellation") == true -> "cancelled"
                event.success -> "success"
                else -> "error"
            }
            toolOutcome(category, outcome, event.durationMs * 1_000_000)
        }
    }

    internal fun toolOutcome(category: String, outcome: String, elapsedNanos: Long) {
        counter("souz.tool.calls", "category", category, "outcome", outcome).increment()
        timer("souz.tool.duration", "category", category, "outcome", outcome)
            .record(elapsedNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    internal fun scrape(): String {
        executions.prepareScrape()
        return registry.scrape()
    }

    override fun close() {
        gc.close()
        registry.close()
    }

    private fun modelLabel(provider: LlmProvider, model: String): String =
        LLMModel.entries.firstOrNull { it.provider == provider && it.alias == model }?.alias
            ?: EmbeddingsModel.entries.firstOrNull { it.provider == provider && it.alias == model }?.alias
            ?: "other"

    internal companion object {
        val DURATION_BUCKETS = longArrayOf(1, 5, 10, 30, 60, 120, 300, 600, 900).map(Duration::ofSeconds).toTypedArray()
    }
}
