package ru.souz.backend.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolExecutionEvent
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.tool.ToolCategory

/** Process-owned meters; identifiers and payloads never become labels. */
class BackendMetrics(nanoTime: () -> Long = System::nanoTime) : AutoCloseable {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val connections = AtomicInteger()
    private val pendingTools = AtomicInteger()
    private val gc = JvmGcMetrics()

    init {
        registry.config().meterFilter(object : MeterFilter {
            override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig =
                if (id.type == Meter.Type.TIMER) {
                    DistributionStatisticConfig.builder().serviceLevelObjectives(*DURATION_BUCKETS).build().merge(config)
                } else config
        })
        listOf(ClassLoaderMetrics(), JvmMemoryMetrics(), JvmThreadMetrics(), ProcessorMetrics(), UptimeMetrics(), gc)
            .forEach { it.bindTo(registry) }
        Gauge.builder("souz.ws.connections.active", connections) { it.get().toDouble() }.register(registry)
        Gauge.builder("souz.pending.tool.calls", pendingTools) { it.get().toDouble() }.register(registry)
        listOf("completed", "failed", "cancelled").forEach { registry.counter("souz.executions", "outcome", it) }
        registry.timer("souz.execution.duration")
        listOf("user_option", "client_tool").forEach { registry.timer("souz.execution.wait.duration", "reason", it) }
        listOf("undelivered", "disconnect", "send_failure", "overtaken").forEach { dropped(it, 0.0) }
    }

    internal val executions = ExecutionMetrics(this, nanoTime)

    internal fun terminal(status: AgentExecutionStatus) = registry.counter("souz.executions", "outcome", status.value).increment()

    internal fun llmAttempt(provider: LlmProvider, model: String, outcome: String, elapsedNanos: Long) =
        recordCall("souz.llm.requests", "souz.llm.request.duration", outcome, elapsedNanos, *modelTags(provider, model))

    internal fun llmUsage(provider: LlmProvider, model: String, usage: LLMResponse.Usage) {
        val tags = modelTags(provider, model)
        registry.counter("souz.llm.tokens", *tags, "direction", "input").increment(usage.promptTokens.coerceAtLeast(0).toDouble())
        registry.counter("souz.llm.tokens", *tags, "direction", "output").increment(usage.completionTokens.coerceAtLeast(0).toDouble())
    }

    internal fun toolStarted() { pendingTools.incrementAndGet() }
    internal fun toolFinished() { pendingTools.decrementAndGet() }
    internal fun socketOpened() { connections.incrementAndGet() }
    internal fun socketClosed() { connections.decrementAndGet() }
    internal fun dropped(reason: String, count: Double = 1.0) { registry.counter("souz.ws.events.dropped", "reason", reason).increment(count) }

    // Client transports record their own typed result, even when invoked inside a Skill helper.
    internal fun toolTelemetry(clientToolNames: Set<String>): AgentTelemetry = object : AgentTelemetry {
        override fun toolExecutionStarted(functionName: String) { if (functionName !in clientToolNames) toolStarted() }
        override fun recordToolExecution(event: AgentToolExecutionEvent) {
            if (event.functionName in clientToolNames) return
            toolFinished()
            val category = ToolCategory.entries.firstOrNull { it.name == event.toolCategory }?.name?.lowercase() ?: "other"
            toolOutcome(category, event.failure?.metricOutcome() ?: "success", event.durationMs * 1_000_000)
        }
    }

    internal fun toolOutcome(category: String, outcome: String, elapsedNanos: Long) =
        recordCall("souz.tool.calls", "souz.tool.duration", outcome, elapsedNanos, "category", category)

    private fun recordCall(counter: String, timer: String, outcome: String, nanos: Long, vararg tags: String) {
        registry.counter(counter, *tags, "outcome", outcome).increment()
        registry.timer(timer, *tags, "outcome", outcome).record(nanos.coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }

    internal fun scrape(): String {
        executions.prepareScrape()
        return registry.scrape()
    }

    override fun close() {
        gc.close()
        registry.close()
    }

    private fun modelTags(provider: LlmProvider, model: String): Array<String> = arrayOf(
        "provider", provider.name.lowercase(), "model",
        LLMModel.entries.firstOrNull { it.provider == provider && it.alias == model }?.alias
            ?: EmbeddingsModel.entries.firstOrNull { it.provider == provider && it.alias == model }?.alias ?: "other",
    )

    private companion object {
        val DURATION_BUCKETS = doubleArrayOf(0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0)
            .map { it * 1_000_000_000 }.toDoubleArray()
    }
}
