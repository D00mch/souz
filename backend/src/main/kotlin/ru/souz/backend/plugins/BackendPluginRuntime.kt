package ru.souz.backend.plugins

import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceConfigurationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.client.clientArgumentsError
import ru.souz.backend.common.BackendToolCapabilityPolicy
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.api.PluginHttp
import ru.souz.plugins.api.PluginInvocation
import ru.souz.plugins.api.PluginLog
import ru.souz.plugins.api.PluginState
import ru.souz.plugins.api.PluginStateValue
import ru.souz.plugins.api.PluginHttpResponse
import ru.souz.tool.ToolCategory
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.TimeUnit

internal data class PluginStatus(
    val id: String,
    val desiredDigest: String,
    val appliedDigest: String?,
    val phase: String,
    val error: String?,
    val liveVersions: Int,
    val desiredRevision: Long,
    val appliedRevision: Long?,
)

internal data class PluginSnapshot(
    val catalog: AgentToolCatalog = AgentToolCatalog(),
    val enabledIds: Set<String> = emptySet(),
    val status: List<PluginStatus> = emptyList(),
    val registryError: String? = null,
)

/**
 * One replica's control plane. The mutex only protects assignments/use counts; plugin code and JDBC
 * never run under it. Independent supervised preparation keeps disables responsive.
 */
// Atomic coroutine entry is required so admission counts always have a finally-based release.
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class BackendPluginRuntime(
    private val repository: PostgresPluginRepository,
    private val config: PluginRuntimeConfig = PluginRuntimeConfig(),
    private val directory: Path = Files.createTempDirectory("souz-plugins-"),
    private val http: PluginHttpClient = PluginHttpClient(),
    private val registry: MeterRegistry? = null,
) {
    private val mutex = Mutex()
    // One bounded view for foreign blocking Java work; no executor or pool per version.
    private val work = CoroutineScope(SupervisorJob() +
        Dispatchers.IO.limitedParallelism(config.concurrentCalls + config.concurrentPreparations + 2))
    private val preparation = Semaphore(config.concurrentPreparations)
    private val slots = linkedMapOf<String, Slot>()
    private val calls = mutableSetOf<Invocation>()
    private val published = MutableStateFlow(PluginSnapshot())
    private var poller: Job? = null
    private var stopping = false
    private var closed = false
    private var registryError: String? = null

    val snapshot: PluginSnapshot get() = published.value

    init {
        registry?.let { meters ->
            Gauge.builder("souz.plugin.registry.available", this) { if (it.snapshot.registryError == null) 1.0 else 0.0 }.register(meters)
        }
    }

    suspend fun start(scope: CoroutineScope) = mutex.withLock {
        check(!stopping)
        if (poller == null) poller = scope.launch {
            while (true) {
                reconcile()
                delay(config.pollMillis)
            }
        }
    }

    /** Also used by operator/reference tests to wait for real PostgreSQL changes, never for version routing. */
    suspend fun reconcile() {
        val desired = try {
            repository.desired()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutex.withLock {
                registryError = diagnostic("registry_unavailable", failure)
                publish()
            }
            return
        }
        mutex.withLock {
            if (stopping) return
            registryError = null
            desired.forEach { row ->
                val slot = slots.getOrPut(row.id) { Slot(row).also { registerMetrics(row.id) } }
                if (row.revision >= slot.desired.revision) slot.desired = row
                if (!slot.desired.enabled) {
                    slot.applied = null
                    slot.appliedRevision = null
                    slot.versions.values.filter { it.phase == Phase.PREPARING }.forEach { it.job?.cancel() }
                }
                apply(slot)
                slot.versions.values.toList().forEach { retire(slot, it) }
            }
            publish()
        }
    }

    suspend fun acquire(owner: String, enabledToolNames: Set<String>? = null): PluginExecutionBinding = mutex.withLock {
        require(owner.isNotBlank())
        check(!stopping) { "Plugin runtime is stopping." }
        val selectedNames = BackendToolCapabilityPolicy.selectExecutionTools(
            EMPTY_CATALOG, EMPTY_CATALOG, enabledToolNames, published.value.catalog,
        ).toolsByCategory.values.flatMap { it.keys }.toSet()
        val selected = slots.values.filter { it.desired.enabled }.mapNotNull { slot ->
            slot.applied?.takeIf { version ->
                version.phase == Phase.READY && version.schemas.any { it.name in selectedNames }
            }?.let { slot.desired.id to it }
        }.toMap()
        selected.values.forEach { it.roots++ }
        PluginExecutionBinding(this, owner, selected, selectedNames)
    }

    internal fun enabled(id: String): Boolean = id in snapshot.enabledIds

    internal suspend fun release(binding: PluginExecutionBinding): Unit = mutex.withLock {
        if (binding.released) return
        binding.released = true
        binding.versions.values.forEach { version ->
            version.roots--
            retire(slots.getValue(version.id), version)
        }
        binding.versions = emptyMap()
        publish()
    }

    // Cancel independently owned work before rethrowing the caller's cancellation.
    @Suppress("SuspendFunSwallowedCancellation")
    internal suspend fun invoke(
        binding: PluginExecutionBinding,
        versionId: String,
        fn: LLMRequest.Function,
        call: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        if (call.name != fn.name) return pluginError(call.name, "plugin_capability_mismatch")
        if (meta.userId != binding.owner) return pluginError(call.name, "plugin_owner_mismatch")
        val arguments = copyPluginJson(call.arguments)
        if (restJsonMapper.writeValueAsBytes(arguments).size > MAX_RESULT_BYTES) return pluginError(call.name, "plugin_input_too_large")
        clientArgumentsError(fn.parameters, restJsonMapper.valueToTree(arguments))?.let {
            return pluginError(call.name, "plugin_invalid_arguments", it)
        }
        val invocation = mutex.withLock {
            val version = binding.versions[versionId]
            if (stopping || binding.released || version == null || !slots.getValue(versionId).desired.enabled) {
                return pluginError(call.name, "plugin_disabled")
            }
            if (calls.size >= config.concurrentCalls) return pluginError(call.name, "plugin_busy")
            val entry = Invocation(version)
            version.calls++
            calls += entry
            // ATOMIC ensures finally runs even if cancellation arrives before the body is dispatched.
            entry.job = work.async(start = CoroutineStart.ATOMIC) {
                val started = System.nanoTime()
                var outcome = "cancelled"
                try {
                    val opened = requireNotNull(version.opened)
                    withContext(PluginClassLoaderContext(opened.loader)) {
                        coroutineScope {
                            @Suppress("UNCHECKED_CAST")
                            val safeCall = LLMResponse.FunctionCall(call.name, arguments as Map<String, Any>)
                            val message = opened.plugin.invoke(safeCall, context(entry, binding.owner, meta))
                            require(message.role == LLMMessageRole.function && message.functionCall == null) { "Invalid plugin result role." }
                            require(message.content.toByteArray().size <= MAX_RESULT_BYTES &&
                                message.attachments.orEmpty().sumOf { it.length } <= 16_384) { "Plugin result is too large." }
                            message.copy(name = call.name, attachments = message.attachments?.toList()).also { outcome = "success" }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    outcome = "error"
                    pluginError(call.name, "plugin_invocation_failed", diagnostic("plugin_invocation_failed", failure))
                } catch (failure: LinkageError) {
                    outcome = "error"
                    pluginError(call.name, "plugin_linkage_failed", diagnostic("plugin_linkage_failed", failure))
                } catch (failure: ServiceConfigurationError) {
                    outcome = "error"
                    pluginError(call.name, "plugin_spi_failed", diagnostic("plugin_spi_failed", failure))
                } finally {
                    registry?.counter("souz.plugin.calls", "plugin", versionId, "outcome", outcome)?.increment()
                    registry?.timer("souz.plugin.call.duration", "plugin", versionId, "outcome", outcome)
                        ?.record(System.nanoTime() - started, TimeUnit.NANOSECONDS)
                    withContext(NonCancellable) {
                        mutex.withLock {
                            calls -= entry
                            version.calls--
                            retire(slots.getValue(version.id), version)
                            publish()
                        }
                    }
                }
            }
            entry
        }
        return try {
            withTimeoutOrNull(config.callMillis) { invocation.job!!.await() }
                ?: pluginError(call.name, "plugin_timeout", "The operation may already have caused an external effect.").also {
                    registry?.counter("souz.plugin.timeouts", "plugin", versionId)?.increment()
                    invocation.job!!.cancel()
                }
        } catch (cancelled: CancellationException) {
            invocation.job!!.cancel()
            throw cancelled
        }
    }

    private fun context(entry: Invocation, owner: String, meta: ToolInvocationMeta): PluginInvocation {
        suspend fun active() = mutex.withLock { check(entry in calls) { "Plugin invocation context has expired." } }
        val state = repository.state(entry.version.id, owner)
        return PluginInvocation(
            meta = meta.copy(userId = owner, attributes = meta.attributes.toMap()),
            state = object : PluginState {
                override suspend fun get(key: String): PluginStateValue? {
                    active()
                    return state.get(key)
                }
                override suspend fun put(key: String, json: String, dataSchemaVersion: Int, expectedRevision: Long?): PluginStateValue? {
                    active()
                    return state.put(key, json, dataSchemaVersion, expectedRevision)
                }
            },
            http = object : PluginHttp {
                override suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): PluginHttpResponse {
                    active()
                    return http.request(method, url, headers, body)
                }
            },
            log = PluginLog { log.info("Plugin {}: {}", entry.version.id, it.replace(Regex("[\\r\\n]"), " ").take(1_000)) },
        )
    }

    private fun apply(slot: Slot) {
        val desired = slot.desired
        if (!desired.enabled) return
        if (slot.applied?.digest == desired.digest) {
            slot.appliedRevision = desired.revision
            return
        }
        val existing = slot.versions[desired.digest]
        if (existing?.phase == Phase.READY) {
            slot.applied = existing
            slot.appliedRevision = desired.revision
            slot.error = null
            slot.versions.values.toList().forEach { retire(slot, it) }
            return
        }
        if (existing != null || slot.versions.values.any { it.phase == Phase.PREPARING }) return
        if (slot.failedRevision == desired.revision && System.nanoTime() < slot.retryAt) return
        if (slot.versions.size >= config.liveVersions) {
            slot.error = "version_capacity: waiting for draining or failed cleanup"
            return
        }
        if (!preparation.tryAcquire()) return
        val candidate = Version(desired.id, desired.digest, desired.revision)
        slot.versions[desired.digest] = candidate
        candidate.job = work.launch(start = CoroutineStart.ATOMIC) { prepare(slot, candidate) }
    }

    // Record the failed revision in NonCancellable, then rethrow; finally owns partial cleanup.
    @Suppress("SuspendFunSwallowedCancellation")
    private suspend fun prepare(slot: Slot, candidate: Version) {
        var initialized = false
        var applied = false
        try {
            withTimeout(config.prepareMillis) {
                val bytes = repository.artifact(candidate.id, candidate.digest)
                withContext(Dispatchers.IO) {
                    candidate.opened = PluginLoader(directory).open(bytes, candidate.id, candidate.digest)
                }
                val opened = requireNotNull(candidate.opened)
                withContext(PluginClassLoaderContext(opened.loader)) {
                    initialized = true
                    coroutineScope { opened.plugin.initialize() }
                    currentCoroutineContext().ensureActive()
                    candidate.schemas = opened.schemas()
                    require(restJsonMapper.writeValueAsBytes(candidate.schemas).size <= MAX_RESULT_BYTES) { "Plugin schemas are too large." }
                }
            }
            mutex.withLock {
                if (!stopping && slot.desired.enabled && slot.desired.revision == candidate.revision &&
                    slot.desired.digest == candidate.digest) {
                    candidate.phase = Phase.READY
                    val old = slot.applied
                    slot.applied = candidate
                    slot.appliedRevision = candidate.revision
                    slot.error = null
                    slot.failedRevision = null
                    applied = true
                    old?.let { retire(slot, it) }
                    log.info("Plugin {} applied digest={}", candidate.id, candidate.digest)
                    publish()
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { failed(slot, candidate, "plugin_preparation_cancelled") }
            throw cancelled
        } catch (failure: Exception) {
            failed(slot, candidate, diagnostic("plugin_preparation_failed", failure))
        } catch (failure: LinkageError) {
            failed(slot, candidate, diagnostic("plugin_linkage_failed", failure))
        } catch (failure: ServiceConfigurationError) {
            failed(slot, candidate, diagnostic("plugin_spi_failed", failure))
        } finally {
            withContext(NonCancellable) {
                if (!applied) {
                    mutex.withLock {
                        candidate.phase = Phase.CLOSING
                        candidate.since = System.nanoTime()
                        publish()
                    }
                    dispose(slot, candidate, initialized)
                }
                preparation.release()
            }
        }
    }

    private suspend fun failed(slot: Slot, candidate: Version, error: String) = mutex.withLock {
        if (!stopping && slot.desired.enabled && slot.desired.revision == candidate.revision) {
            slot.error = error
            slot.failedRevision = candidate.revision
            slot.retryAt = System.nanoTime() + config.retryMillis * 1_000_000
            log.warn("Plugin {} digest={} {}", candidate.id, candidate.digest, error)
        }
        publish()
    }

    private fun retire(slot: Slot, version: Version) {
        if (slot.applied === version || version.phase != Phase.READY || version.roots != 0 || version.calls != 0) return
        version.phase = Phase.CLOSING
        version.since = System.nanoTime()
        version.job = work.launch(start = CoroutineStart.ATOMIC) { dispose(slot, version, initialized = true) }
    }

    // The cleanup deadline is a lifecycle failure. ensureActive still propagates parent cancellation.
    @Suppress("SuspendFunSwallowedCancellation")
    private suspend fun dispose(slot: Slot, version: Version, initialized: Boolean) {
        try {
            version.opened?.let { opened ->
                if (initialized) withContext(PluginClassLoaderContext(opened.loader)) {
                    withTimeout(config.cleanupMillis) { coroutineScope { opened.plugin.shutdown() } }
                }
                withContext(Dispatchers.IO) { opened.close() }
            }
            mutex.withLock {
                slot.versions.remove(version.digest, version)
                version.opened = null
                version.schemas = emptyList()
                publish()
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) {
                // Cleanup has its own deadline; actual completion still precedes this handler.
                currentCoroutineContext().ensureActive()
            }
            cleanupFailed(slot, version, "plugin_cleanup_failed", failure)
        } catch (failure: LinkageError) {
            cleanupFailed(slot, version, "plugin_cleanup_linkage_failed", failure)
        } catch (failure: ServiceConfigurationError) {
            cleanupFailed(slot, version, "plugin_cleanup_spi_failed", failure)
        }
    }

    private suspend fun cleanupFailed(slot: Slot, version: Version, code: String, failure: Throwable) = mutex.withLock {
        version.phase = Phase.FAILED
        version.error = diagnostic(code, failure)
        slot.error = version.error
        log.warn("Plugin {} digest={} {}", version.id, version.digest, version.error)
        publish()
    }

    /** Application executions stop first; this then drains plugin work before HTTP/storage are closed. */
    suspend fun shutdown() {
        mutex.withLock {
            if (closed) return
            stopping = true
            slots.values.forEach { slot ->
                slot.applied = null
                slot.appliedRevision = null
                slot.versions.values.forEach { if (it.phase == Phase.PREPARING) it.job?.cancel() }
                slot.versions.values.toList().forEach { retire(slot, it) }
            }
            calls.forEach { it.job?.cancel() }
            publish()
        }
        poller?.cancelAndJoin()
        check(withTimeoutOrNull(config.shutdownMillis) {
            while (mutex.withLock { slots.values.any { it.versions.isNotEmpty() } }) delay(10)
            true
        } == true) { "Plugin resources did not drain; inspect replica status and restart this replica." }
        work.coroutineContext[Job]!!.cancelAndJoin()
        http.close()
        Files.deleteIfExists(directory)
        mutex.withLock { closed = true }
    }

    private fun publish() {
        val active = slots.values.mapNotNull { it.applied }.flatMap { it.schemas }
        published.value = PluginSnapshot(
            catalog = AgentToolCatalog(mapOf(ToolCategory.PLUGINS to active.map { MetadataTool(it) })),
            enabledIds = if (stopping) emptySet() else slots.values.filter { it.desired.enabled }.mapTo(linkedSetOf()) { it.desired.id },
            status = slots.values.map { slot ->
                val stalled = slot.versions.values.firstOrNull {
                    val elapsed = (System.nanoTime() - it.since) / 1_000_000
                    (it.phase == Phase.PREPARING && elapsed > config.prepareMillis) ||
                        (it.phase == Phase.CLOSING && elapsed > config.cleanupMillis)
                }
                PluginStatus(
                    id = slot.desired.id,
                    desiredDigest = slot.desired.digest,
                    appliedDigest = slot.applied?.digest,
                    phase = if (!slot.desired.enabled) "disabled" else slot.versions[slot.desired.digest]?.phase?.name?.lowercase() ?: "pending",
                    error = slot.versions.values.firstOrNull { it.phase == Phase.FAILED }?.error
                        ?: stalled?.let { "plugin_${it.phase.name.lowercase()}_stalled: actual work has not exited" } ?: slot.error,
                    liveVersions = slot.versions.size,
                    desiredRevision = slot.desired.revision,
                    appliedRevision = slot.appliedRevision,
                )
            },
            registryError = registryError,
        )
    }

    private class Slot(var desired: DesiredPlugin) {
        var applied: Version? = null
        var appliedRevision: Long? = null
        val versions = linkedMapOf<String, Version>()
        var error: String? = null
        var failedRevision: Long? = null
        var retryAt: Long = 0
    }

    internal class Version(val id: String, val digest: String, val revision: Long) {
        var opened: OpenPlugin? = null
        var schemas: List<LLMRequest.Function> = emptyList()
        var phase = Phase.PREPARING
        var roots = 0
        var calls = 0
        var job: Job? = null
        var since: Long = System.nanoTime()
        var error: String? = null
    }

    internal enum class Phase { PREPARING, READY, CLOSING, FAILED }
    private class Invocation(val version: Version) { var job: Deferred<LLMRequest.Message>? = null }

    private class MetadataTool(override val fn: LLMRequest.Function) : LLMToolSetup {
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            pluginError(functionCall.name, "plugin_execution_binding_required")
    }

    private fun registerMetrics(id: String) {
        val meters = registry ?: return
        Gauge.builder("souz.plugin.versions.loaded", this) { runtime ->
            runtime.snapshot.status.firstOrNull { it.id == id }?.liveVersions?.toDouble() ?: 0.0
        }.tag("plugin", id).register(meters)
        Gauge.builder("souz.plugin.applied.revision", this) { runtime ->
            runtime.snapshot.status.firstOrNull { it.id == id }?.appliedRevision?.toDouble() ?: 0.0
        }.tag("plugin", id).register(meters)
        Gauge.builder("souz.plugin.application.error", this) { runtime ->
            if (runtime.snapshot.status.firstOrNull { it.id == id }?.error != null) 1.0 else 0.0
        }.tag("plugin", id).register(meters)
    }

    private companion object {
        val EMPTY_CATALOG = AgentToolCatalog()
        const val MAX_RESULT_BYTES = 1_048_576
        val log = LoggerFactory.getLogger(BackendPluginRuntime::class.java)
    }
}

internal class PluginExecutionBinding(
    private val runtime: BackendPluginRuntime,
    internal val owner: String,
    internal var versions: Map<String, BackendPluginRuntime.Version>,
    selectedNames: Set<String>,
) {
    internal var released = false
    val catalog = AgentToolCatalog(mapOf(ToolCategory.PLUGINS to versions.flatMap { (id, version) ->
        version.schemas.filter { it.name in selectedNames }.map { fn ->
            object : LLMToolSetup {
                override val fn = fn
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                    pluginError(functionCall.name, "plugin_identity_required")
                override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message =
                    runtime.invoke(this@PluginExecutionBinding, id, fn, functionCall, meta)
            }
        }
    }))

    fun isEnabled(name: String): Boolean = runtime.enabled(name.removePrefix("plugin.").substringBefore('.'))
    suspend fun release() = withContext(NonCancellable) { runtime.release(this@PluginExecutionBinding) }
}

private fun diagnostic(code: String, failure: Throwable): String = "$code: ${failure.javaClass.name.take(180)}"

private fun pluginError(name: String, code: String, message: String = "Plugin operation is unavailable."): LLMRequest.Message =
    LLMRequest.Message(LLMMessageRole.function,
        restJsonMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))), name = name)
