package ru.souz.backend.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import ru.souz.backend.app.BackendApplicationScope
import ru.souz.backend.app.BackendRuntimeResources
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

class PluginJdbcLivenessTest {
    @Test fun `admission deadline and application join escape stalled JDBC without starting the handler`() = runBlocking {
        jdbcScenario("select enabled from jvm_plugins") { host ->
            val application = BackendApplicationScope()
            val starts = AtomicInteger()
            val call = application.async { host.invoke { _, _ -> starts.incrementAndGet(); "{}" } }
            withTimeout(5_000) { host.proxy.entered.await() }
            val reply = withTimeout(1_500) { call.await() }
            assertEquals("plugin_queue_timeout", restJsonMapper.readTree(reply.content).errorCode())
            withTimeout(1_000) { application.cancelAndJoin() }
            withTimeout(3_000) { while (host.fatals.get() == 0) delay(10) }
            assertEquals(0, starts.get())
            host.proxy.resume()
        }
    }

    @Test fun `cancelled admission returns its late lease and never calls the handler`() = runBlocking {
        jdbcScenario("select enabled from jvm_plugins") { host ->
            val application = BackendApplicationScope()
            val starts = AtomicInteger()
            application.launch { host.invoke { _, _ -> starts.incrementAndGet(); "{}" } }
            withTimeout(5_000) { host.proxy.entered.await() }
            withTimeout(1_000) { application.cancelAndJoin() }
            host.proxy.resume()
            // Shutdown joins host-owned admission and its late release.
            host.binding.release()
            withTimeout(8_000) { while (!host.runtime.shutdown()) delay(10) }
            val successor = assertNotNull(host.peer.claim("alice", host.version.metadata.binding, PluginRuntimeConfig()))
            host.peer.assertOwned(successor)
            host.peer.release(successor)
            assertEquals(0, starts.get())
        }
    }

    @Test fun `successful and timed out handlers keep cleanup watched until outcome IO exits`() = runBlocking {
        for (timeout in listOf(false, true)) jdbcScenario("set recorded = true") { host ->
            val reply = withTimeout(5_000) {
                host.invoke { _, _ -> if (timeout) awaitCancellation() else "{}" }
            }
            if (timeout) assertEquals("plugin_timeout", restJsonMapper.readTree(reply.content).errorCode())
            else assertEquals("{}", reply.content)
            withTimeout(5_000) { host.proxy.entered.await() }
            withTimeout(3_000) { while (host.fatals.get() == 0) delay(10) }
            assertEquals(1, host.runtime.snapshot.status.single().versions.single().calls)
            host.proxy.resume()
            withTimeout(8_000) { while (host.version.calls.isNotEmpty()) delay(10) }
        }
    }

    @Test fun `one lost COMMIT response triggers recovery and a finite JDBC deadline while peers stay healthy`() = runBlocking {
        jdbcScenario("commit after recorded", pauseAll = false) { host ->
            assertEquals("{}", host.invoke { _, _ -> "{}" }.content)
            withTimeout(5_000) { host.proxy.entered.await() }
            withTimeout(3_000) { while (host.fatals.get() == 0) delay(10) }
            val lease = host.version.calls.single().lease
            host.peer.assertOwned(lease)
            assertNull(host.peer.claim("alice", lease.binding, PluginRuntimeConfig()))
            host.binding.release()
            host.peer.retire(lease.binding.id, lease.binding.version, host.peer.registry().single().revision)
            host.runtime.reconcile()
            assertEquals(0, host.runtime.snapshot.status.single().versions.single().executions)
            assertTrue(Files.exists(host.version.path), "Keep the retired JAR until actual JDBC work exits.")
            // Do not resume the proxy: setNetworkTimeout must bound the missing COMMIT reply.
            withTimeout(9_000) { while (host.runtime.snapshot.status.single().versions.isNotEmpty()) delay(10) }
            assertFalse(Files.exists(host.version.path))
            val successor = assertNotNull(host.peer.claim("alice", lease.binding, PluginRuntimeConfig()))
            host.peer.release(lease)
            host.peer.assertOwned(successor)
            host.peer.release(successor)
        }
    }

    @Test fun `poller IO is inside the shutdown budget and application resources remain open on failure`() = runBlocking {
        jdbcScenario("from jvm_plugins p left join") { host ->
            val application = BackendApplicationScope()
            host.runtime.start(application)
            withTimeout(5_000) { host.proxy.entered.await() }
            host.binding.release()
            var sharedClosed = false
            var restarts = 0
            val resources = BackendRuntimeResources(
                cancelAndJoinApplicationWork = application::cancelAndJoin,
                shutdownPlugins = host.runtime::shutdown,
                pluginDidNotDrain = { restarts++ },
                closePluginServices = { sharedClosed = true },
            )
            withTimeout(1_500) { resources.shutdown() }
            assertEquals(1, restarts)
            assertFalse(sharedClosed)
            host.proxy.resume()
            withTimeout(8_000) { while (!host.runtime.shutdown()) delay(10) }
        }
    }

    @Test fun `pool acquisition cannot extend the caller queue deadline`() = runBlocking {
        jdbcScenario("unused") { host ->
            val held = List(5) { host.source.connection }
            try {
                val reply = withTimeout(1_500) { host.invoke { _, _ -> error("Handler must not start") } }
                assertEquals("plugin_queue_timeout", restJsonMapper.readTree(reply.content).errorCode())
                withTimeout(3_000) { while (host.fatals.get() == 0) delay(10) }
            } finally { held.forEach { it.close() } }
        }
    }

    private suspend fun jdbcScenario(pattern: String, pauseAll: Boolean = true, block: suspend (Host) -> Unit) {
        val fixture = PluginFixture()
        val pin = fixture.install("kotlin-v1", 0)
        val proxy = PgResponseGate(fixture.dataSource.jdbcUrl, pauseAll)
        val source = HikariDataSource(HikariConfig().apply {
            jdbcUrl = proxy.url
            username = fixture.dataSource.username
            password = fixture.dataSource.password
            schema = fixture.dataSource.schema
            addDataSourceProperty("currentSchema", schema)
            maximumPoolSize = 5
            minimumIdle = 0
            connectionTimeout = 1_000
            validationTimeout = 500
        })
        val warm = List(5) { source.connection }
        warm.forEach { it.close() }
        val repository = PostgresPluginRepository(source)
        val services = runtimeServices(repository)
        val fatals = AtomicInteger()
        val timing = fixture.config.copy(queueMillis = 200, callMillis = 500,
            leaseMillis = 1_000, renewMillis = 100, graceMillis = 200, cleanupMillis = 200, shutdownMillis = 300)
        val runtime = BackendPluginRuntime(repository, fixture.artifacts, services, timing, onFatal = { fatals.incrementAndGet() })
        var binding: PluginExecutionBinding? = null
        try {
            runtime.reconcile()
            awaitPlugin { runtime.snapshot.defaultBindings == listOf(pin) }
            binding = runtime.acquire("alice", listOf(pin), PLUGIN_FIXTURE_TOOLS)
            proxy.arm(pattern)
            val version = binding.versions.single()
            block(Host(proxy, source, runtime, binding, version,
                version.functions.single { it.schema.name.endsWith(".report") }, fatals, fixture.peer))
        } finally {
            proxy.resume()
            binding?.release()
            withTimeout(15_000) { while (!runtime.shutdown()) delay(10) }
            services.close()
            source.close()
            proxy.close()
            fixture.close()
        }
    }

    private data class Host(val proxy: PgResponseGate, val source: HikariDataSource, val runtime: BackendPluginRuntime,
        val binding: PluginExecutionBinding, val version: BackendPluginRuntime.Version, val tool: PluginFunction,
        val fatals: AtomicInteger, val peer: PostgresPluginRepository) {
        suspend fun invoke(handler: suspend (String, ru.souz.plugins.api.PluginContext) -> String) =
            runtime.invoke(binding, version, tool.copy(handler = handler),
                LLMResponse.FunctionCall(tool.schema.name, emptyMap()), ToolInvocationMeta("alice"))
    }
}
