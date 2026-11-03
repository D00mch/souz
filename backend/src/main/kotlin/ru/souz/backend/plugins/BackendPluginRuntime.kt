package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceConfigurationError
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.client.clientArgumentsError
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.plugins.api.PluginApi
import ru.souz.plugins.api.PluginException
import ru.souz.plugins.api.PluginFailureKind
import ru.souz.plugins.api.PluginInvocationMeta

internal data class PluginVersionStatus(
    val version: String,
    val digest: String,
    val phase: String,
    val retired: Boolean,
    val quarantined: Boolean,
    val executions: Int,
    val calls: Int,
    val error: String?,
)

internal data class PluginStatus(
    val id: String,
    val desiredDigest: String?,
    val appliedDigest: String?,
    val desiredRevision: Long,
    val appliedRevision: Long?,
    val liveVersions: Int,
    val versions: List<PluginVersionStatus>,
    val error: String?,
)

internal data class PluginSnapshot(
    val catalog: AgentToolCatalog = AgentToolCatalog(),
    val defaultBindings: List<PluginBinding> = emptyList(),
    val status: List<PluginStatus> = emptyList(),
    val registryError: String? = null,
)

/** Per-replica code ownership. JDBC, plugin code, loading and cleanup never run under [mutex]. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class BackendPluginRuntime(
    private val repository: PostgresPluginRepository,
    private val artifacts: S3PluginArtifacts,
    private val services: PluginServices,
    private val config: PluginRuntimeConfig = PluginRuntimeConfig(),
    private val registry: MeterRegistry? = null,
    private val onFatal: suspend () -> Unit = {},
    private val directory: Path = Files.createTempDirectory("souz-plugins-"),
    private val random: Random = Random.Default,
) {
    private val mutex = Mutex()
    private val reconciliation = Mutex()
    // This elastic IO view keeps foreign blocking code separate from DB/control IO; it is not
    // an invocation quota. Suspended healthy invocations do not consume its execution slots.
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(64))
    private val control = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registrations = linkedMapOf<String, PluginRegistration>()
    private val versions = linkedMapOf<String, Version>()
    private val applied = linkedMapOf<String, Version>()
    private val retryAt = mutableMapOf<String, Long>()
    private val preparationErrors = mutableMapOf<String, String>()
    private val calls = mutableSetOf<Invocation>()
    private val published = MutableStateFlow(PluginSnapshot())
    private val metered = mutableSetOf<String>()
    private var poller: Job? = null
    private var registryError: String? = null
    private var stopping = false
    private var fatal = false
    private var closed = false
    val replicaId: String = UUID.randomUUID().toString()
    val snapshot: PluginSnapshot get() = published.value

    /** Admission revalidates lifecycle flags; only reconciliation changes this replica's applied default. */
    suspend fun snapshotForRequest(): PluginSnapshot {
        val observed = snapshot
        val authoritative = try { repository.registry() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw PluginUnavailableException("plugin_registry_unavailable") }
        val eligible = authoritative.filter { it.enabled }.flatMap { it.versions }
            .filter { !it.retired && !it.quarantined }.map { it.binding }.toSet()
        val bindings = observed.defaultBindings.filter { it in eligible }
        val prefixes = bindings.map { "plugin.${it.id}." }
        return observed.copy(defaultBindings = bindings,
            catalog = observed.catalog.filter { _, tool -> prefixes.any(tool.fn.name::startsWith) })
    }

    suspend fun start(scope: CoroutineScope) = mutex.withLock {
        check(!stopping)
        if (poller == null) poller = scope.launch {
            while (true) {
                reconcile()
                delay(config.pollMillis + if (config.jitterMillis == 0L) 0 else random.nextLong(config.jitterMillis + 1))
            }
        }
    }

    /** Immediate at startup, then only the configured poll cadence; also useful to explicit tests. */
    suspend fun reconcile() = reconciliation.withLock {
        val rows = try {
            repository.registry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutex.withLock { registryError = diagnostic("plugin_registry_unavailable", failure); publish() }
            return@withLock
        }
        mutex.withLock {
            if (stopping) return@withLock
            registryError = null
            rows.forEach { row ->
                if (row.revision < (registrations[row.id]?.revision ?: -1)) return@forEach
                registrations[row.id] = row
                registerMetrics(row.id)
                row.versions.forEach { metadata ->
                    versions[metadata.binding.digest]?.metadata = metadata
                    if (row.enabled && !metadata.retired && !metadata.quarantined &&
                        metadata.binding.digest !in versions && now() >= (retryAt[metadata.binding.digest] ?: 0)) {
                        createVersion(metadata)
                    }
                }
            }
            refreshApplied()
            versions.values.toList().forEach(::retire)
            publish()
        }
    }

    /** Explicit persisted pins only: a resume can never silently acquire the current default. */
    suspend fun acquire(owner: String, bindings: List<PluginBinding>, selectedNames: Set<String>): PluginExecutionBinding {
        require(owner.isNotBlank() && bindings.distinctBy { it.id }.size == bindings.size)
        val acquired = mutableListOf<Version>()
        var transferred = false
        try {
            for (binding in bindings) {
                if (!binding.digest.matches(Regex("[a-f0-9]{64}"))) throw PluginUnavailableException("plugin_restore_invalid")
                val metadata = try { repository.version(binding) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { throw PluginUnavailableException("plugin_restore_unavailable") }
                    ?: throw PluginUnavailableException("plugin_restore_missing")
                if (metadata.quarantined) throw PluginUnavailableException("plugin_quarantined")
                while (true) {
                    val (version, closing) = mutex.withLock {
                        check(!stopping) { "Plugin runtime is stopping." }
                        val current = versions[binding.digest] ?: createVersion(metadata)
                        require(current.binding == binding) { "Pinned artifact identity differs." }
                        if (current.phase == Phase.FAILED) throw PluginUnavailableException("plugin_restore_failed")
                        if (current.phase != Phase.CLOSING) {
                            current.roots++
                            acquired += current
                        }
                        current to (current.phase == Phase.CLOSING)
                    }
                    if (closing) {
                        if (withTimeoutOrNull(config.prepareMillis) { version.disposed.await(); true } != true) {
                            throw PluginUnavailableException("plugin_restore_timeout")
                        }
                        continue
                    }
                    if (withTimeoutOrNull(config.prepareMillis) { version.ready.await(); true } != true) {
                        throw PluginUnavailableException("plugin_restore_timeout")
                    }
                    break
                }
            }
            return PluginExecutionBinding(this, owner, acquired.associateBy { it.binding.id }, selectedNames).also { transferred = true }
        } finally {
            if (!transferred) withContext(NonCancellable) {
                mutex.withLock { acquired.forEach { it.roots--; retire(it) }; publish() }
            }
        }
    }

    internal suspend fun release(binding: PluginExecutionBinding) = mutex.withLock {
        if (binding.released) return@withLock
        binding.released = true
        binding.versions.values.forEach { it.roots--; retire(it) }
        binding.versions = emptyMap()
        publish()
    }

    internal fun enabled(binding: PluginBinding): Boolean {
        val status = snapshot.status.firstOrNull { it.id == binding.id } ?: return true
        return status.versions.firstOrNull { it.digest == binding.digest }?.quarantined != true &&
            status.error != "plugin_disabled"
    }

    internal suspend fun invoke(
        binding: PluginExecutionBinding,
        version: Version,
        tool: LoadedPluginTool,
        call: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        if (call.name != tool.schema.name || meta.userId != binding.owner) return pluginError(call.name, "plugin_identity_mismatch")
        val json = restJsonMapper.writeValueAsString(call.arguments)
        if (json.toByteArray().size > PluginApi.MAX_JSON_BYTES) return pluginError(call.name, "plugin_input_too_large")
        clientArgumentsError(tool.schema.parameters, restJsonMapper.readTree(json))?.let {
            return pluginError(call.name, "plugin_invalid_arguments", it)
        }
        val invocation = mutex.withLock {
            if (stopping || binding.released || version.phase != Phase.READY || !locallyEnabled(version)) {
                return pluginError(call.name, "plugin_disabled")
            }
            val entry = Invocation(version, binding.owner, call.name)
            version.calls++
            calls += entry
            // Atomic entry guarantees finally releases accounting even when cancelled before dispatch.
            entry.job = work.launch(start = CoroutineStart.ATOMIC) { execute(entry, tool, json, meta) }
            entry.queueTimer = control.launch {
                delay(config.queueMillis)
                cancel(entry, "plugin_queue_timeout", technical = false, queuedOnly = true)
            }
            publish()
            entry
        }
        return try {
            invocation.reply.await()
        } finally {
            if (!invocation.reply.isCompleted) withContext(NonCancellable) {
                cancel(invocation, "plugin_cancelled", technical = false)
            }
        }
    }

    private suspend fun execute(entry: Invocation, tool: LoadedPluginTool, json: String, meta: ToolInvocationMeta) {
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val acquired = repository.claim(entry.owner, entry.version.binding, config)
                    mutex.withLock { entry.lease = acquired }
                }
                currentCoroutineContext().ensureActive()
                if (entry.lease != null) break
                delay(100)
            }
            mutex.withLock {
                if (!entry.contextActive || stopping || !locallyEnabled(entry.version)) throw PluginUnavailableException("plugin_disabled")
                entry.permit = entry.version.breaker.admit(now()) ?: throw PluginUnavailableException("plugin_version_circuit_open")
                entry.started = true
                entry.renewal = control.launch { renew(entry) }
                entry.callTimer = control.launch {
                    delay(config.callMillis)
                    cancel(entry, "plugin_timeout", technical = true)
                }
            }
            entry.queueTimer?.cancel()
            val opened = requireNotNull(entry.version.opened)
            val context = services.context(requireNotNull(entry.lease), PluginInvocationMeta(
                entry.owner, meta.conversationId, meta.requestId, meta.locale, meta.timeZone,
            )) {
                mutex.withLock { if (!entry.contextActive) throw CancellationException("Plugin invocation context has expired.") }
            }
            val result = withContext(PluginClassLoaderContext(opened.loader)) {
                coroutineScope { currentCoroutineContext().ensureActive(); tool.handler(json, context) }
            }
            check(result.toByteArray().size <= PluginApi.MAX_JSON_BYTES) { "Plugin result exceeds the size limit." }
            checkNotNull(restJsonMapper.readerFor(JsonNode::class.java)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue<JsonNode>(result)) { "Invalid plugin JSON result." }
            complete(entry, PluginCallOutcome.SUCCESS, LLMRequest.Message(LLMMessageRole.function, result, name = entry.name))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: PluginUnavailableException) {
            complete(entry, PluginCallOutcome.IGNORED, pluginError(entry.name, known.code))
        } catch (known: PluginException) {
            val outcome = if (known.kind == PluginFailureKind.TECHNICAL) PluginCallOutcome.TECHNICAL_FAILURE else PluginCallOutcome.IGNORED
            val code = known.code.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,79}")) } ?: "plugin_operation_failed"
            complete(entry, outcome, pluginError(entry.name, code, known.message.orEmpty().take(2048)))
        } catch (_: IllegalArgumentException) {
            complete(entry, PluginCallOutcome.IGNORED, pluginError(entry.name, "plugin_operation_rejected", "The host rejected an invalid service request."))
        } catch (failure: Exception) {
            failedInvocation(entry, failure)
        } catch (failure: LinkageError) {
            failedInvocation(entry, failure)
        } catch (failure: ServiceConfigurationError) {
            failedInvocation(entry, failure)
        } finally {
            withContext(NonCancellable) {
                complete(entry, PluginCallOutcome.IGNORED, pluginError(entry.name, "plugin_cancelled", "The invocation was cancelled; its outcome may be unknown."))
                mutex.withLock { entry.contextActive = false; entry.exited = true }
                entry.queueTimer?.cancel()
                entry.callTimer?.cancel()
                entry.graceTimer?.cancel()
                entry.outcomeWrite?.join()
                entry.renewal?.cancelAndJoin()
                try { entry.lease?.let { repository.release(it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { log.warn("Plugin lease release failed: {}", failure.javaClass.name) }
                finally { mutex.withLock { calls -= entry; entry.version.calls--; retire(entry.version); publish() } }
            }
        }
    }

    private suspend fun renew(entry: Invocation) {
        while (true) {
            delay(config.renewMillis)
            val renewed = try { repository.renew(requireNotNull(entry.lease), config.leaseMillis) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!renewed) {
                cancel(entry, "plugin_lease_lost", technical = false)
                return
            }
        }
    }

    private suspend fun failedInvocation(entry: Invocation, failure: Throwable) {
        val reference = UUID.randomUUID().toString()
        log.warn("Plugin {} digest={} invocation failed diagnostic={} type={}", entry.version.binding.id,
            entry.version.binding.digest, reference, failure.javaClass.name)
        complete(entry, PluginCallOutcome.TECHNICAL_FAILURE,
            pluginError(entry.name, "plugin_failed", "Plugin operation failed; diagnostic ID: $reference"))
    }

    private suspend fun complete(entry: Invocation, outcome: PluginCallOutcome, response: LLMRequest.Message) = mutex.withLock {
        completeLocked(entry, outcome, response)
    }

    private fun completeLocked(entry: Invocation, outcome: PluginCallOutcome, response: LLMRequest.Message) {
        if (entry.outcome != null) return
        entry.contextActive = false
        entry.outcome = outcome
        entry.permit?.let { entry.version.breaker.record(it, entry.owner, outcome, now()) }
        entry.lease?.let { lease ->
            entry.outcomeWrite = control.launch {
                try { repository.recordOutcome(lease, outcome, config) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { log.warn("Plugin outcome accounting failed: {}", failure.javaClass.name) }
            }
        }
        registry?.counter("souz.plugin.calls", "plugin", entry.version.binding.id, "outcome", outcome.name.lowercase())?.increment()
        entry.reply.complete(response)
    }

    private suspend fun cancel(entry: Invocation, code: String, technical: Boolean, queuedOnly: Boolean = false) {
        mutex.withLock {
            if (entry.exited || entry.outcome != null || (queuedOnly && entry.started)) return@withLock
            completeLocked(entry, if (technical) PluginCallOutcome.TECHNICAL_FAILURE else PluginCallOutcome.IGNORED,
                pluginError(entry.name, code, if (queuedOnly) "The handler did not start before the queue deadline."
                    else "The invocation was cancelled; its outcome may be unknown. Do not automatically retry."))
            entry.contextActive = false
            entry.job?.cancel()
            if (entry.started && entry.graceTimer == null) entry.graceTimer = control.launch {
                delay(config.graceMillis)
                if (mutex.withLock { !entry.exited }) severe(entry.version, entry.incident, "plugin_call_stalled")
            }
        }
    }

    private fun createVersion(metadata: PluginVersion): Version {
        val version = Version(metadata, PluginVersionBreaker(config))
        versions[metadata.binding.digest] = version
        version.job = work.launch(start = CoroutineStart.ATOMIC) { prepare(version) }
        version.watchdog = control.launch {
            delay(config.prepareMillis)
            val stalled = mutex.withLock {
                if (version.phase != Phase.PREPARING) false else {
                    version.error = "plugin_prepare_timeout"
                    version.ready.completeExceptionally(PluginUnavailableException("plugin_restore_failed"))
                    version.job?.cancel()
                    publish()
                    true
                }
            }
            if (stalled) {
                delay(config.graceMillis)
                if (mutex.withLock { version.phase == Phase.PREPARING }) severe(version, version.incident, "plugin_prepare_stalled")
            }
        }
        return version
    }

    private suspend fun prepare(version: Version) {
        var retained = false
        try {
            val input = withContext(Dispatchers.IO) { Files.createTempFile(directory, "download-", ".part") }
            try {
                artifacts.download(version.binding, input)
                // Remain on the foreign-work dispatcher. No suspension separates resource creation
                // from ownership assignment, even if the provider ignores concurrent cancellation.
                version.opened = PluginLoader(directory).open(input, version.binding)
                version.tools = requireNotNull(version.opened).tools()
            } finally { withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(input) } }
            currentCoroutineContext().ensureActive()
            mutex.withLock {
                if (!stopping && !version.metadata.quarantined && (!version.metadata.retired || version.roots > 0)) {
                    version.phase = Phase.READY
                    retained = true
                    retryAt.remove(version.binding.digest)
                    preparationErrors.remove(version.binding.digest)
                    version.ready.complete(Unit)
                    refreshApplied()
                    retire(version)
                    publish()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            preparationFailed(version, diagnostic("plugin_preparation_failed", failure))
        } catch (failure: LinkageError) {
            preparationFailed(version, diagnostic("plugin_linkage_failed", failure))
        } catch (failure: ServiceConfigurationError) {
            preparationFailed(version, diagnostic("plugin_spi_failed", failure))
        } finally {
            withContext(NonCancellable) {
                version.watchdog?.cancel()
                if (!retained) {
                    if (version.error == null) preparationFailed(version, "plugin_preparation_cancelled")
                    version.ready.completeExceptionally(PluginUnavailableException("plugin_restore_failed"))
                    dispose(version)
                }
            }
        }
    }

    private suspend fun preparationFailed(version: Version, code: String) = withContext(NonCancellable) {
        mutex.withLock {
            version.error = code
            preparationErrors[version.binding.digest] = code
            retryAt[version.binding.digest] = now() + config.retryMillis
            version.ready.completeExceptionally(PluginUnavailableException("plugin_restore_failed"))
            log.warn("Plugin {} digest={} {}", version.binding.id, version.binding.digest, code)
            publish()
        }
    }

    /** Explicit retirement, quarantine, or process shutdown permits unloading; idleness alone does not. */
    private fun retire(version: Version) {
        if (version.phase != Phase.READY || version.roots != 0 || version.calls != 0 ||
            (!stopping && !version.metadata.retired && !version.metadata.quarantined)) return
        version.phase = Phase.CLOSING
        version.job = work.launch(start = CoroutineStart.ATOMIC) { dispose(version) }
    }

    private suspend fun dispose(version: Version) {
        mutex.withLock { version.phase = Phase.CLOSING; publish() }
        val watchdog = control.launch {
            delay(config.cleanupMillis + config.graceMillis)
            if (mutex.withLock { version.phase == Phase.CLOSING }) severe(version, version.incident, "plugin_cleanup_stalled")
        }
        try {
            version.opened?.close()
            mutex.withLock {
                versions.remove(version.binding.digest, version)
                version.phase = Phase.DISPOSED
                version.opened = null
                version.tools = emptyList()
                version.disposed.complete(Unit)
                refreshApplied()
                publish()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutex.withLock {
                version.phase = Phase.FAILED
                version.error = diagnostic("plugin_cleanup_failed", failure)
                version.disposed.completeExceptionally(PluginUnavailableException("plugin_cleanup_failed"))
                publish()
            }
            control.launch { delay(config.graceMillis); severe(version, version.incident, "plugin_cleanup_failed") }
        } finally { watchdog.cancel() }
    }

    private suspend fun severe(version: Version, incident: String, code: String) {
        val signal = mutex.withLock {
            version.error = code
            val first = !fatal
            fatal = true
            stopping = true
            applied.clear()
            publish()
            first
        }
        log.error("Plugin {} digest={} severe={} incident={}; replica restart required", version.binding.id, version.binding.digest, code, incident)
        // Persistence must not delay local intake revocation or prevent the process watchdog from
        // starting. A dispatched JDBC write can finish during bounded host shutdown.
        val persisted = control.async {
            try { repository.recordSevereIncident(version.binding, incident, config.severeIncidentThreshold, code, replicaId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { log.warn("Plugin severe-incident persistence failed: {}", failure.javaClass.name) }
        }
        if (withTimeoutOrNull(config.cleanupMillis) { persisted.await(); true } != true) {
            log.error("Plugin severe-incident persistence exceeded its deadline incident={}", incident)
        }
        if (signal) onFatal()
    }

    /** False means resources still belong to actual code; the host must not close shared services. */
    suspend fun shutdown(): Boolean {
        val activeCalls = mutex.withLock {
            if (closed) return true
            stopping = true
            applied.clear()
            versions.values.filter { it.phase == Phase.PREPARING }.forEach { it.job?.cancel() }
            versions.values.toList().forEach(::retire)
            publish()
            calls.toList()
        }
        poller?.cancelAndJoin()
        activeCalls.forEach { cancel(it, "plugin_cancelled", technical = false) }
        val drained = withTimeoutOrNull(config.shutdownMillis) {
            while (mutex.withLock { versions.isNotEmpty() || calls.isNotEmpty() }) delay(10)
            true
        } == true
        if (!drained) return false
        work.coroutineContext[Job]!!.cancelAndJoin()
        control.coroutineContext[Job]!!.cancelAndJoin()
        withContext(Dispatchers.IO) { Files.deleteIfExists(directory) }
        mutex.withLock { closed = true }
        return true
    }

    private fun refreshApplied() {
        registrations.values.forEach { row ->
            val desired = row.versions.firstOrNull { it.binding.digest == row.defaultDigest }
            if (!row.enabled || desired == null || desired.retired || desired.quarantined || stopping) applied.remove(row.id)
            else {
                val ready = versions[desired.binding.digest]?.takeIf { it.phase == Phase.READY }
                if (ready != null) applied[row.id] = ready
                else if (applied[row.id]?.let { old ->
                    old.phase == Phase.READY && row.versions.any { it.binding == old.binding && !it.retired && !it.quarantined }
                } != true) applied.remove(row.id)
            }
        }
    }

    private fun locallyEnabled(version: Version): Boolean = !version.metadata.quarantined &&
        registrations[version.binding.id]?.enabled != false

    private fun publish() {
        val active = if (stopping) emptyList() else applied.values.toList()
        published.value = PluginSnapshot(
            catalog = AgentToolCatalog(active.flatMap { it.tools }.groupBy { it.category }
                .mapValues { (_, tools) -> tools.map { MetadataTool(it.schema) } }),
            defaultBindings = active.map { it.binding },
            status = registrations.values.map { row ->
                val loaded = versions.values.filter { it.binding.id == row.id }
                val current = applied[row.id]
                PluginStatus(row.id, row.defaultDigest, current?.binding?.digest, row.revision,
                    row.revision.takeIf { current?.binding?.digest == row.defaultDigest }, loaded.size,
                    loaded.map { version -> PluginVersionStatus(version.binding.version, version.binding.digest,
                        version.phase.name.lowercase(), version.metadata.retired, version.metadata.quarantined,
                        version.roots, version.calls, version.error) },
                    if (!row.enabled) "plugin_disabled" else loaded.firstNotNullOfOrNull { it.error }
                        ?: preparationErrors[row.defaultDigest])
            },
            registryError = registryError,
        )
    }

    private fun registerMetrics(id: String) {
        val meters = registry ?: return
        if (!metered.add(id)) return
        Gauge.builder("souz.plugin.versions.loaded", this) { runtime ->
            runtime.snapshot.status.firstOrNull { it.id == id }?.liveVersions?.toDouble() ?: 0.0
        }.tag("plugin", id).register(meters)
        Gauge.builder("souz.plugin.applied.revision", this) { runtime ->
            runtime.snapshot.status.firstOrNull { it.id == id }?.appliedRevision?.toDouble() ?: 0.0
        }.tag("plugin", id).register(meters)
        Gauge.builder("souz.plugin.application.error", this) { runtime ->
            if (runtime.snapshot.registryError != null || runtime.snapshot.status.firstOrNull { it.id == id }?.error != null) 1.0 else 0.0
        }.tag("plugin", id).register(meters)
    }

    internal class Version(var metadata: PluginVersion, val breaker: PluginVersionBreaker) {
        val binding: PluginBinding get() = metadata.binding
        val incident = UUID.randomUUID().toString()
        val ready = CompletableDeferred<Unit>()
        val disposed = CompletableDeferred<Unit>()
        var phase = Phase.PREPARING
        var roots = 0
        var calls = 0
        var opened: OpenPlugin? = null
        var tools: List<LoadedPluginTool> = emptyList()
        var job: Job? = null
        var watchdog: Job? = null
        var error: String? = null
    }

    internal enum class Phase { PREPARING, READY, CLOSING, DISPOSED, FAILED }

    private class Invocation(val version: Version, val owner: String, val name: String) {
        val incident = UUID.randomUUID().toString()
        val reply = CompletableDeferred<LLMRequest.Message>()
        var lease: PluginCallLease? = null
        var job: Job? = null
        var renewal: Job? = null
        var queueTimer: Job? = null
        var callTimer: Job? = null
        var graceTimer: Job? = null
        var outcomeWrite: Job? = null
        var permit: PluginVersionBreaker.Permit? = null
        var outcome: PluginCallOutcome? = null
        var contextActive = true
        var started = false
        var exited = false
    }

    private class MetadataTool(override val fn: LLMRequest.Function) : LLMToolSetup {
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            pluginError(functionCall.name, "plugin_execution_binding_required")
    }

    private companion object {
        val log = LoggerFactory.getLogger(BackendPluginRuntime::class.java)
        fun now(): Long = System.nanoTime() / 1_000_000
    }
}

internal class PluginExecutionBinding(
    private val runtime: BackendPluginRuntime,
    internal val owner: String,
    internal var versions: Map<String, BackendPluginRuntime.Version>,
    selectedNames: Set<String>,
) {
    internal var released = false
    val catalog = AgentToolCatalog(versions.values.flatMap { version ->
        version.tools.filter { it.schema.name in selectedNames }.map { tool -> tool.category to object : LLMToolSetup {
            override val fn = tool.schema
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                pluginError(functionCall.name, "plugin_identity_required")
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message =
                runtime.invoke(this@PluginExecutionBinding, version, tool, functionCall, meta)
        } }
    }.groupBy({ it.first }, { it.second }))

    fun isEnabled(name: String): Boolean = versions.values.firstOrNull { name.startsWith("plugin.${it.binding.id}.") }
        ?.let { runtime.enabled(it.binding) } ?: false
    suspend fun release() = withContext(NonCancellable) { runtime.release(this@PluginExecutionBinding) }
}

private fun diagnostic(code: String, failure: Throwable): String = "$code: ${failure.javaClass.name.take(160)}"

private fun pluginError(name: String, code: String, message: String = "Plugin operation is unavailable.") =
    LLMRequest.Message(LLMMessageRole.function,
        restJsonMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))), name = name)
