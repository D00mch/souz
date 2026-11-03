package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.client.clientArgumentsError
import ru.souz.llms.*
import ru.souz.plugins.api.*

internal data class PluginVersionStatus(
    val binding: PluginBinding,
    val phase: String,
    val executions: Int,
    val calls: Int,
    val error: String?,
)
internal data class PluginStatus(
    val id: String,
    val observedRevision: Long,
    val appliedDigest: String?,
    val versions: List<PluginVersionStatus>,
)
internal data class PluginSnapshot(
    val catalog: AgentToolCatalog = AgentToolCatalog(),
    val defaultBindings: List<PluginBinding> = emptyList(),
    val status: List<PluginStatus> = emptyList(),
    val registryError: String? = null,
)

/** One coordinator owns versions. Foreign work and control timers use independent dispatchers. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class BackendPluginRuntime(
    val repository: PostgresPluginRepository,
    val artifacts: S3PluginArtifacts,
    private val services: PluginServices,
    val config: PluginRuntimeConfig = PluginRuntimeConfig(),
    private val registry: MeterRegistry? = null,
    private val onFatal: suspend () -> Unit = {},
    private val directory: Path = Files.createTempDirectory("souz-plugins-"),
    private val random: Random = Random.Default,
) {
    private val mutex = Mutex()
    private val polling = Mutex()
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(64))
    private val control = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val versions = linkedMapOf<PluginBinding, Version>()
    private var registrations = emptyList<PluginRegistration>()
    private val published = MutableStateFlow(PluginSnapshot())
    private val metered = mutableSetOf<String>()
    private var poller: Job? = null
    private var stopping = false
    private var closed = false
    private var fatal = false
    private var registryError: String? = null
    val replicaId: String = UUID.randomUUID().toString()
    val snapshot: PluginSnapshot get() = published.value

    suspend fun start(scope: CoroutineScope) = mutex.withLock {
        check(!stopping)
        if (poller == null) poller = scope.launch {
            while (true) {
                reconcile()
                delay(config.pollMillis + if (config.jitterMillis == 0L) 0 else random.nextLong(config.jitterMillis + 1))
            }
        }
    }

    suspend fun reconcile() = polling.withLock {
        try {
            val rows = repository.registry()
            mutex.withLock {
                if (stopping) return@withLock
                registrations = rows
                registryError = null
                for (row in rows) {
                    meters(row.id)
                    for (metadata in row.versions) {
                        versions[metadata.binding]?.metadata = metadata
                        if (!metadata.retired && !metadata.quarantined) {
                            val existing = versions[metadata.binding]
                            if (existing == null || existing.disposed.isCompleted && now() >= existing.retryAt) load(metadata)
                        }
                    }
                }
                versions.values.toList().forEach(::retire)
                publish()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutex.withLock { registryError = diagnostic("plugin_registry_unavailable", failure); publish() } }
    }

    /** Selection uses the applied catalog, with lifecycle bans checked at request admission. */
    suspend fun snapshotForRequest(): PluginSnapshot {
        val current = snapshot
        val eligible = repository.registry().filter { it.enabled }.flatMap { it.versions }
            .filter { !it.retired && !it.quarantined }.map { it.binding }.toSet()
        val pins = current.defaultBindings.filter { it in eligible }
        return current.copy(defaultBindings = pins, catalog = current.catalog.filter { _, tool ->
            pins.any { tool.fn.name.startsWith("plugin." + it.id + ".") }
        })
    }

    suspend fun acquire(owner: String, bindings: List<PluginBinding>, selectedNames: Set<String>): PluginExecutionBinding {
        require(owner.isNotBlank() && bindings.distinctBy { it.id }.size == bindings.size)
        val acquired = mutableListOf<Version>()
        var transferred = false
        try {
            for (pin in bindings) {
                requireIdentity(pin)
                val metadata = repository.version(pin) ?: unavailable("plugin_restore_missing")
                if (metadata.quarantined) unavailable("plugin_quarantined")
                while (true) {
                    val (version, closing) = mutex.withLock {
                        check(!stopping)
                        val entry = versions[pin]?.takeUnless { it.disposed.isCompleted } ?: load(metadata)
                        if (entry.phase == Phase.FAILED && !entry.disposed.isCompleted) unavailable("plugin_restore_failed")
                        if (entry.phase != Phase.CLOSING) { entry.roots++; acquired += entry }
                        entry to (entry.phase == Phase.CLOSING)
                    }
                    if (closing) {
                        if (withTimeoutOrNull(config.prepareMillis) { version.disposed.await(); true } != true) unavailable("plugin_restore_timeout")
                    } else {
                        if (withTimeoutOrNull(config.prepareMillis) { version.ready.await(); true } != true) unavailable("plugin_restore_timeout")
                        break
                    }
                }
            }
            return PluginExecutionBinding(this, owner, acquired.toList(), selectedNames).also { transferred = true }
        } finally {
            if (!transferred) withContext(NonCancellable) {
                mutex.withLock { acquired.forEach { it.roots--; retire(it) }; publish() }
            }
        }
    }

    internal suspend fun release(binding: PluginExecutionBinding) = mutex.withLock {
        if (!binding.released) {
            binding.released = true
            binding.versions.forEach { it.roots--; retire(it) }
            publish()
        }
    }

    internal suspend fun invoke(binding: PluginExecutionBinding, version: Version, tool: PluginFunction,
        call: LLMResponse.FunctionCall, meta: ToolInvocationMeta): LLMRequest.Message {
        if (meta.userId != binding.owner || call.name != tool.schema.name) return pluginError(call.name, "plugin_identity_mismatch")
        val json = restJsonMapper.writeValueAsString(call.arguments)
        if (json.toByteArray().size > PluginApi.MAX_JSON_BYTES) return pluginError(call.name, "plugin_input_too_large")
        clientArgumentsError(tool.schema.parameters, restJsonMapper.readTree(json))?.let { return pluginError(call.name, "plugin_invalid_arguments") }
        var lease: PluginCallLease? = null
        try {
            val admitted = withTimeoutOrNull(config.queueMillis) {
                while (lease == null) {
                    currentCoroutineContext().ensureActive()
                    mutex.withLock { if (stopping || binding.released) unavailable("plugin_disabled") }
                    // A cancelled JDBC handoff must still return ownership to this finally block.
                    withContext(NonCancellable) { lease = repository.claim(binding.owner, version.metadata.binding, config) }
                    if (lease == null) delay(100)
                }
                true
            }
            if (admitted != true) return pluginError(call.name, "plugin_queue_timeout", "The handler did not start.")
            val entry = mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (stopping || binding.released || version.phase != Phase.READY) unavailable("plugin_disabled")
                val probe = version.admit(now()) ?: unavailable("plugin_version_circuit_open")
                Invocation(version, requireNotNull(lease), call.name, probe).also { entry ->
                    version.calls += entry
                    entry.job = work.launch(start = CoroutineStart.ATOMIC) { execute(entry, tool, json, meta) }
                    lease = null
                    publish()
                }
            }
            return try {
                withTimeoutOrNull(config.callMillis) { entry.reply.await() }
                    ?: stop(entry, "plugin_timeout", PluginCallOutcome.TECHNICAL_FAILURE)
            } finally {
                if (!entry.reply.isCompleted) withContext(NonCancellable) { stop(entry, "plugin_cancelled", PluginCallOutcome.IGNORED) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: PluginUnavailableException) { return pluginError(call.name, known.code) }
        finally { withContext(NonCancellable) { lease?.let { repository.release(it) } } }
    }

    private suspend fun execute(entry: Invocation, tool: PluginFunction, json: String, meta: ToolInvocationMeta) {
        val renewal = control.launch {
            while (true) {
                delay(config.renewMillis)
                val owned = try { repository.renew(entry.lease, config.leaseMillis) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (!owned) { stop(entry, "plugin_lease_lost", PluginCallOutcome.IGNORED); break }
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            val context = services.context(entry.lease, PluginInvocationMeta(entry.lease.ownerId, meta.conversationId, meta.requestId, meta.locale, meta.timeZone)) {
                mutex.withLock { if (!entry.active) throw CancellationException("Plugin invocation has ended.") }
            }
            val result = withContext(PluginClassLoaderContext(requireNotNull(entry.version.code).loader)) {
                coroutineScope { tool.handler(json, context) }
            }
            require(result.toByteArray().size <= PluginApi.MAX_JSON_BYTES) { "Invalid plugin result size." }
            require(!restJsonMapper.readerFor(JsonNode::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue<JsonNode>(result).isMissingNode)
            finish(entry, PluginCallOutcome.SUCCESS, LLMRequest.Message(LLMMessageRole.function, result, name = entry.name))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            val known = failure as? PluginException
            val ignored = failure is PluginUnavailableException || known?.kind?.let { it != PluginFailureKind.TECHNICAL } == true
            val code = (failure as? PluginUnavailableException)?.code ?: known?.code
                ?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,79}")) } ?: "plugin_failed"
            val reference = UUID.randomUUID().toString()
            log.warn("Plugin {} invocation {} failed diagnostic={} type={}", entry.lease.binding.id, entry.lease.token, reference, failure.javaClass.name)
            finish(entry, if (ignored) PluginCallOutcome.IGNORED else PluginCallOutcome.TECHNICAL_FAILURE,
                pluginError(entry.name, code, known?.message?.take(2048) ?: "Plugin operation failed; diagnostic: $reference"))
        } finally { withContext(NonCancellable) {
            finish(entry, PluginCallOutcome.IGNORED, pluginError(entry.name, "plugin_cancelled"))
            entry.grace?.cancel()
            entry.accounting?.join()
            renewal.cancelAndJoin()
            try { repository.release(entry.lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { log.warn("Plugin lease release failed: {}", failure.javaClass.name) }
            finally { mutex.withLock { entry.version.calls -= entry; retire(entry.version); publish() } }
        } }
    }

    private suspend fun finish(entry: Invocation, outcome: PluginCallOutcome, response: LLMRequest.Message) = mutex.withLock {
        if (entry.reply.complete(response)) {
            entry.active = false
            entry.version.record(entry, outcome, now())
            entry.accounting = control.launch {
                try { repository.recordOutcome(entry.lease, outcome, config) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { log.warn("Plugin outcome persistence failed: {}", failure.javaClass.name) }
            }
            registry?.counter("souz.plugin.calls", "plugin", entry.lease.binding.id, "outcome", outcome.name.lowercase())?.increment()
        }
    }

    private suspend fun stop(entry: Invocation, code: String, outcome: PluginCallOutcome): LLMRequest.Message {
        val response = pluginError(entry.name, code, "The invocation outcome may be unknown. Do not automatically retry.")
        finish(entry, outcome, response)
        mutex.withLock {
            entry.job.cancel()
            if (entry.grace == null) entry.grace = control.launch {
                if (withTimeoutOrNull(config.graceMillis) { entry.job.join(); true } != true) {
                    severe(entry.lease.binding, entry.lease.token.toString(), "plugin_call_stalled")
                }
            }
        }
        return entry.reply.await()
    }

    private fun load(metadata: PluginVersion): Version {
        val entry = Version(metadata, directory.resolve(UUID.randomUUID().toString() + ".jar"))
        versions[metadata.binding] = entry
        entry.job = work.launch(start = CoroutineStart.ATOMIC) {
            val task = currentCoroutineContext().job
            val deadline = control.launch {
                delay(config.prepareMillis)
                val timedOut = mutex.withLock {
                    if (entry.phase != Phase.PREPARING) false else {
                        entry.ready.completeExceptionally(PluginUnavailableException("plugin_restore_timeout"))
                        task.cancel()
                        true
                    }
                }
                if (timedOut && withTimeoutOrNull(config.graceMillis) { task.join(); true } != true) severe(metadata.binding, entry.incident, "plugin_prepare_stalled")
            }
            var ready = false
            try {
                artifacts.download(metadata.binding, entry.path)
                entry.code = PluginCode(entry.path, metadata.binding)
                val functions = requireNotNull(entry.code).functions()
                currentCoroutineContext().ensureActive()
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    entry.functions = functions
                    entry.phase = Phase.READY
                    ready = true
                    entry.ready.complete(Unit)
                    retire(entry)
                    publish()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                entry.error = diagnostic("plugin_preparation_failed", failure)
            } finally { withContext(NonCancellable) {
                deadline.cancel()
                if (!ready) {
                    entry.error = entry.error ?: "plugin_preparation_cancelled"
                    entry.retryAt = now() + config.retryMillis
                    entry.ready.completeExceptionally(PluginUnavailableException("plugin_restore_failed"))
                    dispose(entry, task)
                }
            } }
        }
        return entry
    }

    private fun retire(entry: Version) {
        if (entry.disposed.isCompleted) { if (stopping) versions.remove(entry.metadata.binding, entry); return }
        if (entry.phase == Phase.READY && entry.roots == 0 && entry.calls.isEmpty() &&
            (stopping || entry.metadata.retired || entry.metadata.quarantined)) {
            entry.phase = Phase.CLOSING
            entry.job = work.launch(start = CoroutineStart.ATOMIC) {
                val task = currentCoroutineContext().job
                withContext(NonCancellable) { dispose(entry, task) }
            }
        }
    }

    private suspend fun dispose(entry: Version, task: Job) {
        mutex.withLock { entry.phase = Phase.CLOSING; publish() }
        val deadline = control.launch {
            delay(config.cleanupMillis)
            task.cancel()
            if (withTimeoutOrNull(config.graceMillis) { task.join(); true } != true) severe(entry.metadata.binding, entry.incident, "plugin_cleanup_stalled")
        }
        try {
            entry.code?.close()
            Files.deleteIfExists(entry.path)
            mutex.withLock {
                entry.code = null
                entry.functions = emptyList()
                entry.disposed.complete(Unit)
                entry.phase = Phase.FAILED
                if (entry.error == null || stopping) versions.remove(entry.metadata.binding, entry)
                publish()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            mutex.withLock { entry.phase = Phase.FAILED; entry.error = diagnostic("plugin_cleanup_failed", failure); publish() }
            control.launch { delay(config.graceMillis); severe(entry.metadata.binding, entry.incident, "plugin_cleanup_failed") }
        } finally { deadline.cancel() }
    }

    private suspend fun severe(binding: PluginBinding, incident: String, code: String) {
        val first = mutex.withLock { val first = !fatal; fatal = true; stopping = true; publish(); first }
        log.error("Plugin {} severe incident {} reason={} replica={}", binding.id, incident, code, replicaId)
        val record = control.async {
            try { repository.recordSevereIncident(binding, incident, config.severeIncidentThreshold, code, replicaId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { log.error("Plugin incident persistence failed: {}", failure.javaClass.name) }
        }
        withTimeoutOrNull(config.cleanupMillis) { record.await() }
        if (first) onFatal()
    }

    suspend fun shutdown(): Boolean {
        val calls = mutex.withLock {
            if (closed) return true
            stopping = true
            versions.values.filter { it.phase == Phase.PREPARING }.forEach { it.job.cancel() }
            versions.values.toList().forEach(::retire)
            publish()
            versions.values.flatMap { it.calls }.toList()
        }
        poller?.cancelAndJoin()
        calls.forEach { stop(it, "plugin_cancelled", PluginCallOutcome.IGNORED) }
        if (withTimeoutOrNull(config.shutdownMillis) { while (mutex.withLock { versions.isNotEmpty() }) delay(10); true } != true) return false
        work.coroutineContext.job.cancelAndJoin()
        control.coroutineContext.job.cancelAndJoin()
        withContext(Dispatchers.IO) { Files.deleteIfExists(directory) }
        mutex.withLock { closed = true }
        return true
    }

    private fun publish() {
        val active = if (stopping) emptyList() else registrations.mapNotNull { row ->
            val selected = row.versions.find { it.binding.digest == row.defaultDigest }
            if (!row.enabled || selected == null || selected.retired || selected.quarantined) null else {
                versions[selected.binding]?.takeIf { it.phase == Phase.READY } ?: snapshot.defaultBindings.find { it.id == row.id }?.let { old ->
                    versions[old]?.takeIf { it.phase == Phase.READY && !it.metadata.retired && !it.metadata.quarantined }
                }
            }
        }
        published.value = PluginSnapshot(
            AgentToolCatalog(active.flatMap { it.functions }.groupBy { it.category }.mapValues { (_, functions) ->
                functions.map { function ->
                    val schema = function.schema
                    object : LLMToolSetup {
                        override val fn = schema
                        override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = pluginError(fn.name, "plugin_identity_required")
                    }
                }
            }), active.map { it.metadata.binding }, registrations.map { row ->
                PluginStatus(row.id, row.revision, active.find { it.metadata.binding.id == row.id }?.metadata?.binding?.digest,
                    versions.values.filter { it.metadata.binding.id == row.id }.map {
                        val phase = if (it.phase == Phase.FAILED && !it.disposed.isCompleted) "cleanup_failed" else it.phase.name.lowercase()
                        PluginVersionStatus(it.metadata.binding, phase, it.roots, it.calls.size, it.error)
                    })
            }, registryError,
        )
    }

    private fun meters(id: String) {
        val meters = registry ?: return
        if (!metered.add(id)) return
        Gauge.builder("souz.plugin.versions.loaded", this) { runtime ->
            runtime.snapshot.status.find { it.id == id }?.versions?.count { it.phase != "failed" }?.toDouble() ?: 0.0
        }.tag("plugin", id).register(meters)
        Gauge.builder("souz.plugin.observed.revision", this) { runtime ->
            runtime.snapshot.status.find { it.id == id }?.observedRevision?.toDouble() ?: 0.0
        }.tag("plugin", id).register(meters)
        Gauge.builder("souz.plugin.application.error", this) { runtime ->
            if (runtime.snapshot.registryError != null || runtime.snapshot.status.find { it.id == id }?.versions?.any { it.error != null } == true) 1.0 else 0.0
        }.tag("plugin", id).register(meters)
    }

    internal inner class Version(var metadata: PluginVersion, val path: Path) {
        val ready = CompletableDeferred<Unit>()
        val disposed = CompletableDeferred<Unit>()
        val incident = UUID.randomUUID().toString()
        var code: PluginCode? = null
        var functions = emptyList<PluginFunction>()
        var phase = Phase.PREPARING
        var roots = 0
        val calls = mutableSetOf<Invocation>()
        lateinit var job: Job
        var error: String? = null
        var retryAt = 0L
        private val failures = ArrayDeque<Pair<Long, String>>()
        private var openUntil = 0L
        private var probe = false
        fun admit(time: Long): Boolean? {
            if (time < openUntil || probe) return null
            return (openUntil != 0L).also { probe = it }
        }
        fun record(call: Invocation, outcome: PluginCallOutcome, time: Long) {
            while (failures.firstOrNull()?.let { time - it.first >= config.versionWindowMillis } == true) failures.removeFirst()
            if (call.probe) {
                probe = false
                when (outcome) {
                    PluginCallOutcome.SUCCESS -> { openUntil = 0; failures.clear() }
                    PluginCallOutcome.TECHNICAL_FAILURE -> openUntil = time + config.cooldownMillis
                    PluginCallOutcome.IGNORED -> Unit
                }
            } else if (outcome == PluginCallOutcome.TECHNICAL_FAILURE) {
                failures += time to call.lease.ownerId
                if (failures.size >= config.versionFailureThreshold && failures.map { it.second }.distinct().size >= config.versionMinimumUsers) {
                    openUntil = time + config.cooldownMillis
                }
            }
        }
    }
    internal class Invocation(val version: Version, val lease: PluginCallLease, val name: String, val probe: Boolean) {
        val reply = CompletableDeferred<LLMRequest.Message>()
        lateinit var job: Job
        var active = true
        var grace: Job? = null
        var accounting: Job? = null
    }
    internal enum class Phase { PREPARING, READY, CLOSING, FAILED }
    private companion object {
        val log = LoggerFactory.getLogger(BackendPluginRuntime::class.java)
        fun now() = System.nanoTime() / 1_000_000
        fun diagnostic(code: String, failure: Throwable) = code + ": " + failure.javaClass.name.take(160)
    }
}

internal class PluginExecutionBinding(
    private val runtime: BackendPluginRuntime, val owner: String,
    val versions: List<BackendPluginRuntime.Version>, selectedNames: Set<String>,
) {
    var released = false
    val catalog = AgentToolCatalog(versions.flatMap { version ->
        version.functions.filter { it.schema.name in selectedNames }.map { function -> function.category to object : LLMToolSetup {
            override val fn = function.schema
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall) = pluginError(fn.name, "plugin_identity_required")
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta) =
                runtime.invoke(this@PluginExecutionBinding, version, function, functionCall, meta)
        } }
    }.groupBy({ it.first }, { it.second }))
    suspend fun release() = withContext(NonCancellable) { runtime.release(this@PluginExecutionBinding) }
}

internal fun pluginError(name: String, code: String, message: String = "Plugin operation is unavailable.") =
    LLMRequest.Message(LLMMessageRole.function, restJsonMapper.writeValueAsString(mapOf("error" to mapOf("code" to code, "message" to message))), name = name)
