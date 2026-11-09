package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.backend.client.clientArgumentsError
import ru.souz.llms.*
import ru.souz.plugins.api.*

/** One coordinator owns versions. Foreign work and control timers use independent dispatchers. */
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
    private val stateMutex = Mutex()
    private val pollingMutex = Mutex() // only for `reconcile`
    // Foreign blocking code cannot occupy DB/control IO. This dispatcher is not an invocation quota.
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(64))
    private val control = CoroutineScope(SupervisorJob() + controlDispatcher)
    private val shutdownScope = CoroutineScope(SupervisorJob() + controlDispatcher)
    private var shutdownTask: Deferred<Unit>? = null
    private val admissions = mutableSetOf<Admission>()
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

    suspend fun start(scope: CoroutineScope) = stateMutex.withLock {
        check(!stopping)
        scope.coroutineContext.ensureActive()
        if (poller == null) poller = control.launch {
            while (true) {
                reconcile()
                val jitter = if (config.jitterMillis == 0L) 0 else random.nextLong(config.jitterMillis + 1)
                delay((config.pollMillis + jitter).milliseconds)
            }
        }
    }

    /**
     * Applies the PSQL registry to this replica,
     * scheduling version loads, cleanup, and publishing the tool catalog.
     */
    suspend fun reconcile() = pollingMutex.withLock {
        try {
            val rows: List<PluginRegistration> = repository.registry()
            stateMutex.withLock {
                if (stopping) return@withLock
                registrations = rows
                registryError = null
                for ((pluginId: String, _, isPluginEnabled, _, pluginVersions: List<PluginVersion>) in rows) {
                    meters(pluginId)
                    for (metadata: PluginVersion in pluginVersions) {
                        versions[metadata.binding]?.metadata = metadata
                        if (isPluginEnabled && !metadata.retired && !metadata.quarantined) {
                            val existing = versions[metadata.binding]
                            if (existing == null || existing.disposed.isCompleted && now() >= existing.retryAt) {
                                load(metadata)
                            }
                        }
                    }
                }
                versions.values.toList().forEach { it.breaker.prune(now()); retire(it) }
                publish()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            stateMutex.withLock {
                registryError = diagnostic("plugin_registry_unavailable", failure)
                publish()
            }
        }
    }

    /** Selection uses the applied catalog, with lifecycle bans checked at request admission. */
    suspend fun snapshotForRequest(): PluginSnapshot {
        val current = snapshot
        val rows = try {
            repository.registry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable("plugin_registry_unavailable")
        }
        val eligible = rows.asSequence().filter { it.enabled }
            .flatMap { it.versions }
            .filter { !it.retired && !it.quarantined }
            .map { it.binding }
            .toSet()
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
                val metadata = try {
                    repository.version(pin)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unavailable("plugin_restore_unavailable")
                } ?: unavailable("plugin_restore_missing")
                if (metadata.quarantined) unavailable("plugin_quarantined")
                while (true) {
                    val (version, closing) = stateMutex.withLock {
                        check(!stopping)
                        val entry = versions[pin]?.takeUnless { it.disposed.isCompleted } ?: load(metadata)
                        if (entry.phase == Phase.CLEANUP_FAILED) unavailable("plugin_restore_failed")
                        if (entry.phase != Phase.CLOSING) { entry.roots++; acquired += entry }
                        entry to (entry.phase == Phase.CLOSING)
                    }
                    if (closing) {
                        if (withTimeoutOrNull(config.prepareMillis.milliseconds) { version.disposed.await(); true } != true) unavailable("plugin_restore_timeout")
                    } else {
                        if (withTimeoutOrNull(config.prepareMillis.milliseconds) { version.ready.await(); true } != true) unavailable("plugin_restore_timeout")
                        break
                    }
                }
            }
            return PluginExecutionBinding(this, owner, acquired.toList(), selectedNames).also { transferred = true }
        } finally {
            if (!transferred) withContext(NonCancellable) {
                stateMutex.withLock { acquired.forEach { it.roots--; retire(it) }; publish() }
            }
        }
    }

    internal suspend fun release(binding: PluginExecutionBinding) = stateMutex.withLock {
        if (!binding.released) {
            binding.released = true
            binding.versions.forEach { it.roots--; retire(it) }
            publish()
        }
    }

    internal suspend fun invoke(
        binding: PluginExecutionBinding,
        version: Version,
        tool: PluginFunction,
        call: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        if (meta.userId != binding.owner || call.name != tool.schema.name) return pluginError(call.name, "plugin_identity_mismatch")
        val json = restJsonMapper.writeValueAsString(call.arguments)
        if (json.toByteArray().size > PluginApi.MAX_JSON_BYTES) return pluginError(call.name, "plugin_input_too_large")
        clientArgumentsError(tool.schema.parameters, restJsonMapper.readTree(json))?.let { return pluginError(call.name, "plugin_invalid_arguments") }
        val admission = stateMutex.withLock {
            if (stopping || binding.released) return pluginError(call.name, "plugin_disabled")
            admit(binding.owner, version.metadata.binding)
        }
        try {
            val admitted = withTimeoutOrNull(config.queueMillis.milliseconds) {
                admission.ready.await()
            }
            if (admitted != true) return pluginError(call.name, "plugin_queue_timeout", "The handler did not start.")
            val entry = stateMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (stopping || binding.released || version.phase != Phase.READY) unavailable("plugin_disabled")
                val permit = version.breaker.admit(now()) ?: unavailable("plugin_version_circuit_open")
                Invocation(version, requireNotNull(admission.lease), call.name, permit).also { entry ->
                    version.calls += entry
                    entry.job = work.launch(start = CoroutineStart.ATOMIC) { execute(entry, tool, json, meta) }
                    control.launch {
                        if (withTimeoutOrNull(config.callMillis.milliseconds) { entry.reply.await(); true } != true) {
                            stop(entry, "plugin_timeout", PluginCallOutcome.TECHNICAL_FAILURE)
                        }
                    }
                    admission.lease = null // Ownership transfers under the same coordinator lock.
                    admission.handoff.complete(Unit)
                    publish()
                }
            }
            return try {
                entry.reply.await()
            } finally {
                if (!entry.reply.isCompleted) withContext(NonCancellable) { stop(entry, "plugin_cancelled", PluginCallOutcome.IGNORED) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        catch (known: PluginUnavailableException) { return pluginError(call.name, known.code) }
        finally { admission.handoff.complete(Unit) }
    }

    /** The host owns JDBC and late leases; a caller only waits for the handoff. */
    private fun admit(owner: String, binding: PluginBinding): Admission {
        val entry = Admission()
        admissions += entry
        val expires = now() + config.queueMillis
        val task = control.launch(start = CoroutineStart.ATOMIC) {
            try {
                while (!entry.handoff.isCompleted && now() < expires) {
                    withContext(NonCancellable) { entry.lease = repository.claim(owner, binding, config) }
                    if (entry.lease != null) {
                        entry.ready.complete(true)
                        entry.handoff.await()
                        break
                    }
                    delay(100.milliseconds)
                }
                entry.ready.complete(false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                entry.ready.completeExceptionally(failure)
            } finally {
                entry.ready.cancel()
                withContext(NonCancellable) {
                    try { entry.lease?.let { repository.release(it) } }
                    finally { stateMutex.withLock { admissions -= entry } }
                }
            }
        }
        control.launch {
            if (withTimeoutOrNull((config.queueMillis + config.graceMillis).milliseconds) { task.join(); true } != true) {
                severe(binding, entry.incident, "plugin_admission_stalled")
            }
        }
        return entry
    }

    private suspend fun execute(
        entry: Invocation,
        tool: PluginFunction,
        json: String,
        meta: ToolInvocationMeta,
    ) {
        val renewal = control.launch {
            while (true) {
                delay(config.renewMillis.milliseconds)
                val owned = try { repository.renew(entry.lease, config.leaseMillis) }
                catch (cancelled: CancellationException) {
            throw cancelled
        }
                catch (_: Exception) { false }
                if (!owned) { stop(entry, "plugin_lease_lost", PluginCallOutcome.IGNORED); break }
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            val context = services.context(entry.lease, PluginInvocationMeta(entry.lease.ownerId, meta.conversationId, meta.requestId, meta.locale, meta.timeZone)) {
                stateMutex.withLock { if (!entry.active) throw CancellationException("Plugin invocation has ended.") }
            }
            val result = withContext(PluginClassLoaderContext(requireNotNull(entry.version.code).loader)) {
                coroutineScope { tool.handler(json, context) }
            }
            require(result.toByteArray().size <= PluginApi.MAX_JSON_BYTES) { "Invalid plugin result size." }
            require(!restJsonMapper.readerFor(JsonNode::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue<JsonNode>(result).isMissingNode)
            finish(entry, PluginCallOutcome.SUCCESS, LLMRequest.Message(LLMMessageRole.function, result, name = entry.name))
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            val known = failure as? PluginException
            val ignored = failure is PluginUnavailableException || known?.kind?.let { it != PluginFailureKind.TECHNICAL } == true
            val code = (failure as? PluginUnavailableException)?.code ?: known?.code
                ?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,79}")) } ?: "plugin_failed"
            val reference = UUID.randomUUID().toString()
            log.warn("Plugin {} invocation {} failed diagnostic={} type={}", entry.lease.binding.id, entry.lease.token, reference, failure.javaClass.name)
            finish(entry, if (ignored) PluginCallOutcome.IGNORED else PluginCallOutcome.TECHNICAL_FAILURE,
                pluginError(entry.name, code, known?.message?.take(2048) ?: "Plugin operation failed; diagnostic: $reference"))
        } finally {
            withContext(NonCancellable) {
                finish(entry, PluginCallOutcome.IGNORED, pluginError(entry.name, "plugin_cancelled"))
                stateMutex.withLock { watchInvocation(entry) }
                entry.accounting?.join()
                renewal.cancelAndJoin()
                try {
                    repository.release(entry.lease)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.warn("Plugin lease release failed: {}", failure.javaClass.name)
                } finally {
                    stateMutex.withLock {
                        entry.version.calls -= entry
                        retire(entry.version)
                        publish()
                    }
                }
            }
        }
    }

    private suspend fun finish(entry: Invocation, outcome: PluginCallOutcome, response: LLMRequest.Message) = stateMutex.withLock {
        if (entry.reply.complete(response)) {
            entry.active = false
            entry.version.breaker.record(entry.permit, entry.lease.ownerId, outcome, now())
            entry.accounting = control.launch {
                try { repository.recordOutcome(entry.lease, outcome, config) }
                catch (cancelled: CancellationException) {
            throw cancelled
        }
                catch (failure: Exception) { log.warn("Plugin outcome persistence failed: {}", failure.javaClass.name) }
            }
            registry?.counter("souz.plugin.calls", "plugin", entry.lease.binding.id, "outcome", outcome.name.lowercase())?.increment()
        }
    }

    private suspend fun stop(entry: Invocation, code: String, outcome: PluginCallOutcome): LLMRequest.Message {
        val response = pluginError(entry.name, code, "The invocation outcome may be unknown. Do not automatically retry.")
        finish(entry, outcome, response)
        stateMutex.withLock { watchInvocation(entry) }
        requestCancellation(entry.job)
        return entry.reply.await()
    }

    private fun watchInvocation(entry: Invocation) {
        if (entry.grace == null) entry.grace = control.launch {
            if (withTimeoutOrNull(config.graceMillis.milliseconds) { entry.job.join(); true } != true) {
                severe(entry.lease.binding, entry.lease.token.toString(), "plugin_call_stalled")
            }
        }
    }

    // Job.cancel can execute arbitrary synchronous plugin callbacks. Never run it on control.
    private fun requestCancellation(job: Job) { work.launch { job.cancel() } }

    private fun load(metadata: PluginVersion): Version {
        val entry = Version(metadata, directory.resolve(UUID.randomUUID().toString() + ".jar"), PluginVersionBreaker(config))
        versions[metadata.binding] = entry
        val expires = now() + config.prepareMillis
        val deadline = control.launch(start = CoroutineStart.LAZY) {
            delay((expires - now()).coerceAtLeast(0).milliseconds)
            val timedOut = stateMutex.withLock {
                if (entry.phase != Phase.PREPARING) false else {
                    entry.ready.completeExceptionally(PluginUnavailableException("plugin_restore_timeout"))
                    true
                }
            }
            if (timedOut) {
                requestCancellation(entry.job)
                if (withTimeoutOrNull(config.graceMillis.milliseconds) { entry.job.join(); true } != true) {
                    severe(metadata.binding, entry.incident, "plugin_prepare_stalled")
                }
            }
        }
        entry.job = work.launch(start = CoroutineStart.ATOMIC) {
            val task = currentCoroutineContext().job
            var ready = false
            try {
                artifacts.download(metadata.binding, entry.path)
                entry.code = PluginCode(entry.path, metadata.binding)
                val functions = requireNotNull(entry.code).functions()
                currentCoroutineContext().ensureActive()
                stateMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    entry.functions = functions
                    entry.phase = Phase.READY
                    ready = true
                    entry.ready.complete(Unit)
                    retire(entry)
                    publish()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                entry.error = diagnostic("plugin_preparation_failed", failure)
            } finally {
                withContext(NonCancellable) {
                    deadline.cancel()
                    if (!ready) {
                        entry.error = entry.error ?: "plugin_preparation_cancelled"
                        entry.retryAt = now() + config.retryMillis
                        entry.ready.completeExceptionally(PluginUnavailableException("plugin_restore_failed"))
                        val cleanupWatch = watchCleanup(entry, task)
                        try { dispose(entry) } finally { cleanupWatch.cancel() }
                    }
                }
            }
        }
        deadline.start()
        return entry
    }

    private fun retire(entry: Version) {
        if (entry.disposed.isCompleted) {
            if (stopping) versions.remove(entry.metadata.binding, entry)
            return
        }
        if (entry.phase == Phase.READY && entry.roots == 0 && entry.calls.isEmpty() &&
            (stopping || entry.metadata.retired || entry.metadata.quarantined)) {
            entry.phase = Phase.CLOSING
            entry.job = work.launch(start = CoroutineStart.ATOMIC) {
                withContext(NonCancellable) { dispose(entry) }
            }
            watchCleanup(entry, entry.job)
        }
    }

    private fun watchCleanup(entry: Version, task: Job): Job = control.launch {
        if (withTimeoutOrNull(config.cleanupMillis.milliseconds) { task.join(); true } != true) {
            requestCancellation(task)
            if (withTimeoutOrNull(config.graceMillis.milliseconds) { task.join(); true } != true) severe(entry.metadata.binding, entry.incident, "plugin_cleanup_stalled")
        }
    }

    private suspend fun dispose(entry: Version) {
        stateMutex.withLock { entry.phase = Phase.CLOSING; publish() }
        try {
            entry.code?.close()
            @Suppress("BlockingMethodInNonBlockingContext") // This runs cleanup on the plugin work IO dispatcher.
            Files.deleteIfExists(entry.path)
            stateMutex.withLock {
                entry.code = null
                entry.functions = emptyList()
                entry.disposed.complete(Unit)
                entry.phase = if (entry.error == null) Phase.DISPOSED else Phase.FAILED
                if (entry.error == null || stopping) versions.remove(entry.metadata.binding, entry)
                publish()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            stateMutex.withLock {
                entry.phase = Phase.CLEANUP_FAILED
                entry.error = diagnostic("plugin_cleanup_failed", failure)
                publish()
            }
            control.launch { delay(config.graceMillis.milliseconds); severe(entry.metadata.binding, entry.incident, "plugin_cleanup_failed") }
        }
    }

    private suspend fun severe(binding: PluginBinding, incident: String, code: String) {
        val first = stateMutex.withLock {
            val first = !fatal
            fatal = true
            stopping = true
            publish()
            first
        }
        log.error("Plugin {} severe incident {} reason={} replica={}", binding.id, incident, code, replicaId)
        val record = control.async {
            try { repository.recordSevereIncident(binding, incident, config.severeIncidentThreshold, code, replicaId) }
            catch (cancelled: CancellationException) {
            throw cancelled
        }
            catch (failure: Exception) { log.error("Plugin incident persistence failed: {}", failure.javaClass.name) }
        }
        withTimeoutOrNull(config.cleanupMillis.milliseconds) { record.await() }
        if (first) onFatal()
    }

    suspend fun shutdown(): Boolean {
        val task = stateMutex.withLock {
            if (closed) return true
            shutdownTask ?: shutdownScope.async(Dispatchers.IO) { drain() }.also { shutdownTask = it }
        }
        // This observer runs independently of every join, cancellation callback and filesystem IO.
        return shutdownScope.async {
            withTimeoutOrNull(config.shutdownMillis.milliseconds) { task.await(); true } ?: false
        }.await()
    }

    private suspend fun drain() {
        val calls = stateMutex.withLock {
            stopping = true
            versions.values.filter { it.phase == Phase.PREPARING }.forEach { requestCancellation(it.job) }
            admissions.forEach { it.handoff.complete(Unit) }
            versions.values.toList().forEach(::retire)
            publish()
            versions.values.flatMap { it.calls }.toList()
        }
        calls.forEach { stop(it, "plugin_cancelled", PluginCallOutcome.IGNORED) }
        poller?.cancelAndJoin()
        while (stateMutex.withLock { versions.isNotEmpty() || admissions.isNotEmpty() }) delay(10.milliseconds)
        work.coroutineContext.job.cancelAndJoin()
        control.coroutineContext.job.cancelAndJoin()
        withContext(Dispatchers.IO) { Files.deleteIfExists(directory) }
        stateMutex.withLock { closed = true }
    }

    private fun appliedVersion(row: PluginRegistration): Version? {
        if (!row.enabled) return null
        val selected = row.versions.find { it.binding.digest == row.defaultDigest } ?: return null
        if (selected.retired || selected.quarantined) return null
        versions[selected.binding]?.takeIf { it.phase == Phase.READY }?.let { return it }

        // Failed activation may keep the previous default only while it remains eligible.
        val previous = snapshot.defaultBindings.find { it.id == row.id } ?: return null
        return versions[previous]?.takeIf {
            it.phase == Phase.READY && !it.metadata.retired && !it.metadata.quarantined
        }
    }

    private fun publish() {
        val active = if (stopping) emptyList() else registrations.mapNotNull(::appliedVersion)
        val tools = active.flatMap { it.functions }.groupBy { it.category }.mapValues { (_, functions) ->
            functions.map { MetadataTool(it.schema) }
        }
        val status = registrations.map { row ->
            val loaded = versions.values.filter { it.metadata.binding.id == row.id }
            PluginStatus(
                id = row.id,
                observedRevision = row.revision,
                appliedDigest = active.find { it.metadata.binding.id == row.id }?.metadata?.binding?.digest,
                versions = loaded.map {
                    PluginVersionStatus(
                        binding = it.metadata.binding,
                        phase = it.phase.name.lowercase(),
                        executions = it.roots,
                        calls = it.calls.size,
                        error = it.error,
                    )
                },
            )
        }
        published.value = PluginSnapshot(
            catalog = AgentToolCatalog(tools),
            defaultBindings = active.map { it.metadata.binding },
            status = status,
            registryError = registryError,
        )
    }

    private class MetadataTool(override val fn: LLMRequest.Function) : LLMToolSetup {
        override suspend fun invoke(functionCall: LLMResponse.FunctionCall) =
            pluginError(fn.name, "plugin_identity_required")
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

    internal class Version(var metadata: PluginVersion, val path: Path, val breaker: PluginVersionBreaker) {
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
    }

    internal class Invocation(val version: Version, val lease: PluginCallLease, val name: String, val permit: PluginVersionBreaker.Permit) {
        val reply = CompletableDeferred<LLMRequest.Message>()
        lateinit var job: Job
        var active = true
        var grace: Job? = null
        var accounting: Job? = null
    }
    private class Admission {
        val ready = CompletableDeferred<Boolean>()
        val handoff = CompletableDeferred<Unit>()
        val incident = UUID.randomUUID().toString()
        var lease: PluginCallLease? = null
    }
    internal enum class Phase { PREPARING, READY, CLOSING, DISPOSED, FAILED, CLEANUP_FAILED }
    private companion object {
        // Reserved process-wide capacity: neither shared Default CPU work nor foreign IO uses it.
        val controlDispatcher = Executors.newScheduledThreadPool(2) { task ->
            Thread(task, "souz-plugin-control").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val log: Logger = LoggerFactory.getLogger(BackendPluginRuntime::class.java)
        fun now() = System.nanoTime() / 1_000_000
        fun diagnostic(code: String, failure: Throwable) = code + ": " + failure.javaClass.name.take(160)
    }
}
