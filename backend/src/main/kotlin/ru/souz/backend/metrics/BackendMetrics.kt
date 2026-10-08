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
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolCategory

/** Backend-owned meters with bounded labels and no request payloads. */
class BackendMetrics(nanoTime: () -> Long = System::nanoTime) : AutoCloseable {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val sockets = AtomicInteger()
    private val tools = AtomicInteger()
    private val gc = JvmGcMetrics()

    init {
        val buckets = doubleArrayOf(.01, .05, .1, .5, 1.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0)
            .map { it * 1_000_000_000 }.toDoubleArray()
        registry.config().meterFilter(object : MeterFilter {
            override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig =
                if (id.type == Meter.Type.TIMER) {
                    DistributionStatisticConfig.builder().serviceLevelObjectives(*buckets).build().merge(config)
                } else config
        })
        listOf(ClassLoaderMetrics(), JvmMemoryMetrics(), JvmThreadMetrics(), ProcessorMetrics(), UptimeMetrics(), gc)
            .forEach { it.bindTo(registry) }
        Gauge.builder("souz.ws.connections.active", sockets) { it.get().toDouble() }.register(registry)
        Gauge.builder("souz.pending.tool.calls", tools) { it.get().toDouble() }.register(registry)
        listOf("completed", "failed", "cancelled").forEach { registry.counter("souz.executions", "outcome", it) }
        listOf("undelivered", "disconnect", "send_failure", "overtaken").forEach { dropped(it, 0.0) }
    }

    internal val executions = ExecutionMetrics(this, nanoTime)

    internal fun terminal(status: AgentExecutionStatus) =
        registry.counter("souz.executions", "outcome", status.value).increment()

    internal fun llmAttempt(provider: LlmProvider, model: String, outcome: String, elapsedNanos: Long) =
        recordCall("souz.llm.requests", "souz.llm.request.duration", outcome, elapsedNanos, *modelTags(provider, model))

    internal fun llmUsage(provider: LlmProvider, model: String, usage: LLMResponse.Usage) {
        val tags = modelTags(provider, model)
        // Anthropic reports cache reads separately; other providers include them in promptTokens.
        val input = usage.promptTokens.coerceAtLeast(0).toDouble() +
            if (provider == LlmProvider.ANTHROPIC) usage.precachedTokens.coerceAtLeast(0).toDouble() else 0.0
        registry.counter("souz.llm.tokens", *tags, "direction", "input").increment(input)
        registry.counter("souz.llm.tokens", *tags, "direction", "output")
            .increment(usage.completionTokens.coerceAtLeast(0).toDouble())
    }

    internal fun toolStarted() { tools.incrementAndGet() }
    internal fun toolFinished() { tools.decrementAndGet() }
    internal fun socketOpened() { sockets.incrementAndGet() }
    internal fun socketClosed() { sockets.decrementAndGet() }
    internal fun dropped(reason: String, count: Double = 1.0) {
        registry.counter("souz.ws.events.dropped", "reason", reason).increment(count)
    }

    internal fun instrumentTool(tool: LLMToolSetup, category: ToolCategory): LLMToolSetup = object : LLMToolSetup by tool {
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            invoke(functionCall, ToolInvocationMeta.localDefault())

        // Record the delegated failure before Skill helpers turn it into a result message.
        @Suppress("SuspendFunSwallowedCancellation")
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
            val startedAt = System.nanoTime()
            var outcome = "success"
            toolStarted()
            try {
                return tool.invoke(functionCall, meta)
            } catch (failure: Throwable) {
                outcome = failure.metricOutcome()
                throw failure
            } finally {
                toolFinished()
                toolOutcome(category.name.lowercase(), outcome, System.nanoTime() - startedAt)
            }
        }
    }

    internal fun toolTelemetry(excludedToolNames: Set<String>): AgentTelemetry = object : AgentTelemetry {
        override fun toolExecutionStarted(functionName: String) {
            if (functionName !in excludedToolNames) toolStarted()
        }

        override fun recordToolExecution(event: AgentToolExecutionEvent) {
            if (event.functionName in excludedToolNames) return // Catalog tools record at their invocation boundary.
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

    private fun modelTags(provider: LlmProvider, model: String) = arrayOf(
        "provider", provider.name.lowercase(), "model",
        model.takeIf { name ->
            LLMModel.entries.any { it.provider == provider && it.alias == name } ||
                EmbeddingsModel.entries.any { it.provider == provider && it.alias == name }
        } ?: "other",
    )

    internal fun scrape(): String {
        executions.prepareScrape()
        return registry.scrape()
    }

    override fun close() {
        gc.close()
        registry.close()
    }
}
