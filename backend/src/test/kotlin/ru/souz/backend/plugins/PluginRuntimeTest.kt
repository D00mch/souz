package ru.souz.backend.plugins

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

class PluginRuntimeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `update pins old work and disable rejects its subsequent dispatch without closing busy JARs`() = fixture {
        control.block("/v1/invoke")
        install("v1")
        val old = binding()
        coroutineScope {
            val running = async { invoke(old, arguments = mapOf("text" to "a,b\nc,d", "hold" to true)) }
            control.await("/v1/invoke")
            install("v2")
            val next = binding()
            assertTrue(invoke(next).startsWith("v2:"))
            assertFalse(control.seen("/v1/shutdown"))
            assertEquals(2, jars())
            control.release("/v1/invoke")
            assertTrue(running.await().startsWith("v1:"))
            repository.disable("reference", 2)
            runtime.reconcile()
            assertTrue(runtime.snapshot.catalog.toolsByCategory.values.all { it.isEmpty() })
            assertEquals("plugin_disabled", error(invoke(old)))
            old.release()
            next.release()
            eventually { jars() == 0 }
            assertTrue(control.seen("/v1/shutdown"))
            assertTrue(control.seen("/v2/shutdown"))
        }
    }

    @Test
    fun `timeout keeps actual use until foreign blocking work really exits`() = fixture(callMillis = 250) {
        control.block("/v1/invoke")
        install("v1")
        val old = binding()
        coroutineScope {
            val running = async { invoke(old, arguments = mapOf("text" to "a", "hold" to true)) }
            control.await("/v1/invoke")
            assertEquals("plugin_timeout", error(running.await()))
            old.release()
            install("v2")
            assertFalse(control.seen("/v1/shutdown"))
            assertEquals(2, jars())
            control.release("/v1/invoke")
            eventually { control.seen("/v1/shutdown") && jars() == 1 }
        }
    }

    @Test
    fun `rollback reuses a draining version and capacity defers a third version`() = fixture {
        install("v1")
        val first = binding()
        install("v2")
        val second = binding()
        val v3 = artifact("v3")
        repository.install(v3)
        repository.select("reference", "v3", 2)
        runtime.reconcile()
        assertTrue(runtime.snapshot.status.single().error!!.startsWith("version_capacity"))
        repository.select("reference", "v1", 3)
        runtime.reconcile()
        assertTrue(invoke(binding()).startsWith("v1:"))
        assertEquals(1, control.count("/v1/init"))
        repository.select("reference", "v3", 4)
        runtime.reconcile()
        first.release()
        bindings.filter { it !== second }.forEach { it.release() }
        second.release()
        applied("v3", PluginArtifact.inspect(v3).digest)
        assertTrue(invoke(binding()).startsWith("v3:"))
    }

    @Test
    fun `rollback waits for closing to finish before loading the same digest again`() = fixture {
        install("v1")
        val first = binding()
        install("v2")
        control.block("/v1/shutdown")
        first.release()
        control.await("/v1/shutdown")
        repository.select("reference", "v1", 2)
        runtime.reconcile()
        assertTrue(invoke(binding()).startsWith("v2:"))
        assertEquals(1, control.count("/v1/init"))
        control.release("/v1/shutdown")
        applied("v1", PluginArtifact.inspect(artifact("v1")).digest)
        assertEquals(2, control.count("/v1/init"))
        assertTrue(invoke(binding()).startsWith("v1:"))
    }

    @Test
    fun `acquire and retirement keep both calls on a live pinned implementation`() = fixture {
        install("v1")
        coroutineScope {
            val users = (1..8).map {
                async {
                    repeat(5) {
                        val execution = runtime.acquire("alice")
                        try {
                            val first = invoke(execution).substringBefore(':')
                            delay(1)
                            assertEquals(first, invoke(execution).substringBefore(':'))
                        } finally { execution.release() }
                    }
                }
            }
            install("v2")
            users.awaitAll()
        }
    }

    @Test
    fun `superseded preparation cannot publish and does not block disabling another plugin`() = fixture {
        install("v1")
        control.block("/v2/init")
        repository.install(artifact("v2"))
        repository.select("reference", "v2", 1)
        runtime.reconcile()
        control.await("/v2/init")
        install("v1", id = "secondary")
        repository.disable("secondary", 1)
        repository.disable("reference", 2)
        runtime.reconcile()
        assertTrue(runtime.snapshot.catalog.toolsByCategory.values.all { it.isEmpty() })
        control.release("/v2/init")
        eventually { jars() == 0 }
        assertFalse(runtime.snapshot.status.any { it.appliedDigest != null })
        assertTrue(control.seen("/v2/shutdown"))
    }

    @Test
    fun `preparation for an older desired revision is discarded before the next one publishes`() = fixture {
        install("v1")
        val v1 = runtime.snapshot.status.single().appliedDigest
        control.block("/v2/init")
        control.block("/v3/init")
        repository.install(artifact("v2"))
        repository.select("reference", "v2", 1)
        runtime.reconcile()
        control.await("/v2/init")
        val v3 = artifact("v3")
        repository.install(v3)
        repository.select("reference", "v3", 2)
        runtime.reconcile()
        control.release("/v2/init")
        eventually { runtime.reconcile(); control.seen("/v3/init") }
        assertEquals(v1, runtime.snapshot.status.single().appliedDigest)
        assertTrue(control.seen("/v2/shutdown"))
        control.release("/v3/init")
        applied("v3", PluginArtifact.inspect(v3).digest)
    }

    @Test
    fun `failed candidates clean partial resources and a valid revision still applies`() = fixture {
        install("v1")
        for ((index, mode) in listOf("init-fail", "duplicate", "schema", "spi").withIndex()) {
            val version = "bad$index"
            val bytes = artifact(version, mode = mode)
            repository.install(bytes)
            repository.select("reference", version, (index + 1).toLong())
            runtime.reconcile()
            eventually {
                val status = runtime.snapshot.status.single()
                status.desiredDigest == PluginArtifact.inspect(bytes).digest && status.error != null &&
                    status.phase == "pending" && status.liveVersions == 1 && jars() == 1
            }
            if (mode == "spi") assertFalse(control.seen("/$version/init"))
            else assertTrue(control.seen("/$version/shutdown"))
            assertTrue(invoke(binding()).startsWith("v1:"))
        }
        val good = artifact("v2")
        repository.install(good)
        repository.select("reference", "v2", 5)
        applied("v2", PluginArtifact.inspect(good).digest)
        assertTrue(invoke(binding()).startsWith("v2:"))
    }

    @Test
    fun `ordinary and lazy linkage failures stay local and user identity cannot override the bound owner`() = fixture {
        install("v1", mode = "invoke-linkage")
        val linkage = binding()
        assertEquals("plugin_linkage_failed", error(invoke(linkage)))
        linkage.release()
        install("v2", mode = "invoke-fail")
        val ordinary = binding()
        assertEquals("plugin_invocation_failed", error(invoke(ordinary)))
        ordinary.release()
        val good = artifact("v3")
        repository.install(good)
        repository.select("reference", "v3", 2)
        applied("v3", PluginArtifact.inspect(good).digest)
        val alice = binding("alice")
        val bob = binding("bob")
        val value = restJsonMapper.readTree(invoke(alice, "state", mapOf("key" to "key", "json" to "{\"x\":1}", "userId" to "bob")))
        assertEquals("alice", value["owner"].asText())
        val other = restJsonMapper.readTree(invoke(bob, "state", mapOf("key" to "key")))
        assertTrue(other["json"].isNull)
        assertEquals("plugin_owner_mismatch", error(invoke(alice, meta = ToolInvocationMeta("bob"))))
        assertEquals("plugin_invalid_arguments", error(invoke(alice, arguments = emptyMap())))
    }

    private fun fixture(callMillis: Long = 2_000, block: suspend Fixture.() -> Unit) = runBlocking {
        val config = postgresAppConfig(newPostgresSchema("plugin_lifecycle")).postgres
        PostgresDataSourceFactory.create(config).use { source ->
            source.connection.use { it.createStatement().use { sql -> sql.execute("insert into users(id) values ('alice'), ('bob')") } }
            PluginControl().use { control ->
                val runtime = BackendPluginRuntime(PostgresPluginRepository(source),
                    PluginRuntimeConfig(callMillis = callMillis, shutdownMillis = 5_000, retryMillis = 10_000), directory)
                val fixture = Fixture(PostgresPluginRepository(source), runtime, control, directory)
                try { fixture.block() } finally {
                    control.releaseAll()
                    fixture.bindings.forEach { it.release() }
                    runtime.shutdown()
                }
            }
        }
    }

    private class Fixture(
        val repository: PostgresPluginRepository,
        val runtime: BackendPluginRuntime,
        val control: PluginControl,
        private val directory: Path,
    ) {
        val bindings = mutableListOf<PluginExecutionBinding>()

        fun artifact(version: String, id: String = "reference", mode: String = ""): ByteArray {
            val fixture = if (version == "v1") "kotlin-v1" else "kotlin-v2"
            val base = Files.readAllBytes(Path.of(System.getProperty("plugin.fixture.$fixture")))
            val resources = mapOf(
                "fixture-version.txt" to version,
                "fixture-mode.txt" to mode,
                "fixture-control-url.txt" to "${control.url}/$version",
            ) + if (mode == "spi") mapOf("META-INF/services/ru.souz.plugins.api.SkillPlugin" to "missing.Provider") else emptyMap()
            return repackagePlugin(base, mapOf("Souz-Plugin-Id" to id, "Souz-Plugin-Version" to version), resources)
        }

        suspend fun install(version: String, id: String = "reference", mode: String = "") {
            val bytes = artifact(version, id, mode)
            val path = Files.createTempFile("operator-plugin-", ".jar")
            try {
                Files.write(path, bytes)
                val revision = repository.desired().firstOrNull { it.id == id }?.revision ?: 0
                PluginOperator(repository).execute(listOf("install", path.toString(), revision.toString()))
            } finally { Files.deleteIfExists(path) }
            applied(version, PluginArtifact.inspect(bytes).digest, id)
        }

        suspend fun applied(version: String, digest: String, id: String = "reference") = eventually {
            runtime.reconcile()
            runtime.snapshot.status.any { it.id == id && it.appliedDigest == digest }
        }

        suspend fun binding(owner: String = "alice") = runtime.acquire(owner).also { bindings += it }
        fun jars(): Int = Files.list(directory).use { it.filter { path -> path.toString().endsWith(".jar") }.count().toInt() }

        suspend fun invoke(
            binding: PluginExecutionBinding,
            tool: String = "report",
            arguments: Map<String, Any> = mapOf("text" to "a,b"),
            meta: ToolInvocationMeta = ToolInvocationMeta(binding.owner),
        ): String {
            val name = "plugin.reference.$tool"
            return binding.catalog.toolsByCategory.values.firstNotNullOf { it[name] }.invoke(LLMResponse.FunctionCall(name, arguments), meta).content
        }
    }

    private companion object {
        fun error(content: String): String = restJsonMapper.readTree(content)["error"]["code"].asText()
        suspend fun eventually(condition: suspend () -> Boolean) = withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}

internal class PluginControl : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val events = ConcurrentLinkedQueue<String>()
    private val gates = ConcurrentHashMap<String, CompletableFuture<Unit>>()
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                events += path
                gates[path]?.get(10, TimeUnit.SECONDS)
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("ok".toByteArray()) }
            } finally { exchange.close() }
        }
        server.start()
    }

    fun block(path: String) { gates[path] = CompletableFuture() }
    fun release(path: String) { gates[path]?.complete(Unit) }
    fun releaseAll() { gates.values.forEach { it.complete(Unit) } }
    fun seen(path: String): Boolean = events.contains(path)
    fun count(path: String): Int = events.count { it == path }
    suspend fun await(path: String) = withTimeout(5_000) { while (!seen(path)) delay(10) }

    override fun close() {
        releaseAll()
        server.stop(0)
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
