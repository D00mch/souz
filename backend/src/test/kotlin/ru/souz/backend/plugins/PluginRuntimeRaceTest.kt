package ru.souz.backend.plugins

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta

class PluginRuntimeRaceTest {
    @Test fun replyCancellationTimeoutLeaseLossAndCleanupPermutationsDrain() = runtimeTest {
        val pin = install("kotlin-v1", 0)
        val services = runtimeServices(repository)
        val meters = SimpleMeterRegistry()
        val fatal = AtomicInteger()
        val timing = config.copy(callMillis = 300, renewMillis = 20, leaseMillis = 1000, graceMillis = 2000,
            userFailureThreshold = 1000, versionFailureThreshold = 1000)
        val host = BackendPluginRuntime(repository, artifacts, services, timing, registry = meters, onFatal = { fatal.incrementAndGet() })
        host.reconcile()
        val binding = host.acquire("alice", listOf(pin), PLUGIN_FIXTURE_TOOLS)
        val version = binding.versions.single()
        val original = version.functions.single { it.schema.name.endsWith(".report") }
        var admitted = 0
        try {
            repeat(10) { round -> for (mode in listOf("success", "cancel", "timeout", "lost", "race")) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val exited = CompletableDeferred<Unit>()
                val tool = original.copy(handler = { _, context ->
                    entered.complete(Unit)
                    try { release.await(); "{}" }
                    finally {
                        withContext(NonCancellable) {
                            if (mode == "cancel" || mode == "timeout" || mode == "lost") {
                                assertFailsWith<CancellationException> { context.state.get("cleanup") }
                            }
                            delay(5)
                            exited.complete(Unit)
                        }
                    }
                })
                var successor: PluginCallLease? = null
                coroutineScope {
                    val caller = async { host.invoke(binding, version, tool, LLMResponse.FunctionCall(tool.schema.name, emptyMap()), ToolInvocationMeta("alice")) }
                    withTimeout(5000) { entered.await() }; admitted++
                    when (mode) {
                        "success" -> { release.complete(Unit); assertEquals("{}", caller.await().content) }
                        "cancel" -> { caller.cancelAndJoin() }
                        "timeout" -> assertEquals("plugin_timeout", ru.souz.llms.restJsonMapper.readTree(caller.await().content).errorCode())
                        "lost" -> {
                            dataSource.connection.use { c -> c.createStatement().use { s ->
                                s.executeUpdate("update jvm_plugin_calls set lease_until = clock_timestamp() - interval '1 second' where owner_id='alice'")
                            } }
                            successor = requireNotNull(peer.claim("alice", pin, config))
                            assertEquals("plugin_lease_lost", ru.souz.llms.restJsonMapper.readTree(caller.await().content).errorCode())
                        }
                        else -> {
                            launch { release.complete(Unit) }
                            caller.cancelAndJoin()
                        }
                    }
                }
                withTimeout(5000) { exited.await(); while (host.snapshot.status.single().versions.single().calls != 0) delay(5) }
                successor?.let { peer.assertOwned(it); peer.release(it) }
                val free = requireNotNull(peer.claim("alice", pin, config))
                peer.release(free)
                assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
            } }
            val counted = meters.find("souz.plugin.calls").counters().sumOf { it.count() }.toInt()
            assertEquals(admitted, counted)
            assertEquals(0, fatal.get())
        } finally {
            binding.release()
            assertTrue(host.shutdown())
            services.close()
            meters.close()
        }
    }

    @Test fun exactAcquisitionRetirementAndDisposalRacesDoNotSpinOrStrandWaiters() = runtimeTest {
        val pin = install("kotlin-v1", 0)
        val host = runtime()
        host.reconcile()
        val initial = bind(host, pin)
        repository.retire(pin.id, pin.version, 1)
        host.reconcile()
        initial.release()
        withTimeout(30000) {
            coroutineScope {
                List(6) { worker -> launch {
                    repeat(12) {
                        val binding = host.acquire("alice", listOf(pin), PLUGIN_FIXTURE_TOOLS)
                        try { assertEquals("v1", binding.call("report")["version"].asText()) }
                        finally { binding.release() }
                        if ((worker + it) % 2 == 0) host.reconcile()
                        yield()
                    }
                } }.joinAll()
            }
            while (host.snapshot.status.single().versions.isNotEmpty()) delay(5)
        }
    }

    @Test fun registryMutationsAndInvocationStateLocksHaveNoObservedCycle() = runtimeTest {
        val pin = install("kotlin-v1", 0)
        val host = runtime(config.copy(callMillis = 5000))
        host.reconcile()
        val binding = bind(host, pin)
        withTimeout(30000) { coroutineScope {
            val calls = launch {
                repeat(60) {
                    val result = binding.call("state")
                    assertTrue(result.has("count") || result.errorCode() == "plugin_disabled" || result.errorCode() == "plugin_quarantined")
                }
            }
            val mutations = launch {
                repeat(30) { index ->
                    val revision = repository.registry().single().revision
                    when (index % 5) {
                        0 -> repository.disable(pin.id, revision)
                        1 -> repository.select(pin.id, pin.version, revision)
                        2 -> repository.retire(pin.id, pin.version, revision)
                        3 -> repository.recordSevereIncident(pin, "race-$index", 1000)
                        else -> repository.reenable(pin.id, pin.version, revision)
                    }
                    host.reconcile()
                }
                repository.select(pin.id, pin.version, repository.registry().single().revision)
            }
            calls.join(); mutations.join()
        } }
        awaitPlugin { host.snapshot.status.single().versions.single().calls == 0 }
        assertTrue(binding.call("state").has("count"))
    }
}
