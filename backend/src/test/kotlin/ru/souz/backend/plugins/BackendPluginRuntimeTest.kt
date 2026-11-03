package ru.souz.backend.plugins

import com.fasterxml.jackson.databind.JsonNode
import com.zaxxer.hikari.HikariDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.channels.ChannelProviderRegistry
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.newPostgresSchema
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver

class BackendPluginRuntimeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `nonretired versions stay ready and retired exact pins reload after draining`(): Unit = runtimeTest {
        val first = install("kotlin-v1", 0)
        val local = runtime()
        local.reconcile()
        awaitPlugin { local.snapshot.defaultBindings == listOf(first) }
        val pinned = bind(local, first)
        val second = install("kotlin-v2", 1)
        local.reconcile()
        awaitPlugin { local.snapshot.defaultBindings == listOf(second) }
        assertEquals(setOf("v1", "v2"), local.snapshot.status.single().versions.map { it.version }.toSet())
        assertEquals("v1", pinned.call("report")["version"].asText())
        repository.retire("reference", "v1", 2)
        local.reconcile()
        assertEquals(2, local.snapshot.status.single().liveVersions)
        pinned.release()
        awaitPlugin { local.snapshot.status.single().versions.map { it.version } == listOf("v2") }
        val restored = bind(local, first)
        assertEquals("v1", restored.call("report")["version"].asText())
        restored.release()
        awaitPlugin { local.snapshot.status.single().liveVersions == 1 }
        repository.retire("reference", "v2", 3)
        local.reconcile()
        awaitPlugin { local.snapshot.status.single().liveVersions == 0 }
        assertEquals(emptyList(), local.snapshot.defaultBindings)
        assertEquals(emptyList(), local.acquire("alice", emptyList(), setOf("plugin.reference.report")).catalog.toolsByCategory.values.flatMap { it.keys })
    }

    @Test
    fun `new admission validates lifecycle independently from polling while exact continuations remain valid`(): Unit = runtimeTest {
        val first = install("kotlin-v1", 0)
        val local = runtime()
        local.reconcile()
        awaitPlugin { local.snapshot.defaultBindings == listOf(first) }
        assertEquals(listOf(first), local.snapshotForRequest().defaultBindings)
        repository.retire("reference", "v1", 1)
        assertEquals(listOf(first), local.snapshot.defaultBindings)
        assertEquals(emptyList(), local.snapshotForRequest().defaultBindings)
        assertTrue(local.snapshotForRequest().catalog.toolsByCategory.values.all { it.isEmpty() })
        val continuation = bind(local, first)
        assertEquals("v1", continuation.call("report")["version"].asText())
    }

    @Test
    fun `replicas serialize state updates across selected versions while owners stay isolated`(): Unit = runtimeTest {
        val first = install("kotlin-v1", 0)
        val second = install("kotlin-v2", 1)
        val local = runtime()
        val peer = runtime()
        local.reconcile(); peer.reconcile()
        val one = bind(local, first)
        val two = bind(peer, second)
        val other = bind(peer, second, "bob")
        val values = coroutineScope {
            (0 until 12).map { index -> async { (if (index % 2 == 0) one else two).call("state")["count"].asInt() } }.awaitAll()
        }
        assertEquals((1..12).toSet(), values.toSet())
        assertEquals(1, other.call("state", owner = "bob")["count"].asInt())
        assertEquals(13, two.call("state")["count"].asInt())
        assertEquals("plugin_identity_mismatch", two.call("report", owner = "bob").errorCode())
    }

    @Test
    fun `database bans reject subsequent pinned calls before replica polling catches up`(): Unit = runtimeTest {
        val first = install("kotlin-v1", 0)
        val local = runtime()
        local.reconcile()
        val pinned = bind(local, first)
        repeat(3) { repository.recordSevereIncident(first, "incident-$it", 3) }
        assertEquals("plugin_quarantined", pinned.call("report").errorCode())
        assertEquals("plugin_quarantined", assertFailsWith<PluginUnavailableException> { bind(local, first) }.code)
        repository.reenable("reference", "v1", repository.registry().single().revision)
        assertEquals("v1", pinned.call("report")["version"].asText())
        repository.disable("reference", repository.registry().single().revision)
        assertEquals("plugin_disabled", pinned.call("report").errorCode())
    }

    @Test
    fun `failed activation preserves eligible applied default and retains diagnostic after cleanup`(): Unit = runtimeTest {
        val first = install("kotlin-v1", 0)
        val local = runtime(config.copy(retryMillis = 5_000))
        local.reconcile()
        awaitPlugin { local.snapshot.defaultBindings == listOf(first) }
        val broken = repackagePlugin(pluginFixture("kotlin-v2"), directory.resolve("broken.jar"),
            resources = mapOf("fixture-construction-fail" to byteArrayOf()))
        val unavailable = PluginArtifact.inspect(broken).binding
        artifacts.store(broken, unavailable)
        repository.install(unavailable, 1)
        local.reconcile()
        awaitPlugin { local.snapshot.status.single().error != null && local.snapshot.status.single().versions.none { it.digest == unavailable.digest } }
        assertEquals(listOf(first), local.snapshot.defaultBindings)
        val missing = unavailable.copy(version = "missing", digest = "0".repeat(64))
        repository.install(missing, 2)
        local.reconcile()
        awaitPlugin { local.snapshot.status.single().error != null && local.snapshot.status.single().versions.none { it.digest == missing.digest } }
        assertEquals(listOf(first), local.snapshot.defaultBindings)
        assertEquals("plugin_restore_failed", assertFailsWith<PluginUnavailableException> { bind(local, missing) }.code)
        repository.retire("reference", "v1", 3)
        local.reconcile()
        assertEquals(emptyList(), local.snapshot.defaultBindings)
    }

    @Test
    fun `startup poll is immediate and subsequent polls sample fresh jitter after completion`(): Unit = runtimeTest {
        val samples = CopyOnWriteArrayList<Pair<Long, Long>>()
        val jitter = object : Random() {
            override fun nextBits(bitCount: Int): Int = error("Unexpected random source")
            override fun nextLong(until: Long): Long {
                val value = listOf(0L, 40L, 80L)[samples.size % 3]
                samples += System.nanoTime() / 1_000_000 to value
                return value
            }
        }
        val first = install("kotlin-v1", 0)
        val local = runtime(config.copy(pollMillis = 100, jitterMillis = 80), jitter)
        coroutineScope {
            local.start(this)
            awaitPlugin { local.snapshot.defaultBindings == listOf(first) && samples.size >= 4 }
            samples.zipWithNext().forEach { (previous, next) -> assertTrue(next.first - previous.first >= 100 + previous.second - 3) }
            assertTrue(samples.map { it.second }.toSet().size == 3)
            assertTrue(local.shutdown())
        }
    }

    @Test
    fun `no faster registry loop updates a replica between configured polls`(): Unit = runtimeTest {
        val first = install("kotlin-v1", 0)
        val local = runtime(config.copy(pollMillis = 2_000, jitterMillis = 0))
        coroutineScope {
            local.start(this)
            awaitPlugin { local.snapshot.defaultBindings == listOf(first) }
            val second = install("kotlin-v2", 1)
            delay(1_100)
            assertEquals(listOf(first), local.snapshot.defaultBindings)
            awaitPlugin { local.snapshot.defaultBindings == listOf(second) }
            assertTrue(local.shutdown())
        }
    }

    @Test
    fun `user breaker spans replicas and classified business errors do not advance it`(): Unit = runtimeTest {
        val first = install("kotlin-v1", 0)
        val settings = config.copy(userFailureThreshold = 2, cooldownMillis = 250)
        val one = bind(runtime(settings), first)
        val two = bind(runtime(settings), first)
        repeat(3) { assertEquals("fixture_failure", one.call("fail", mapOf("kind" to "BUSINESS")).errorCode()) }
        assertEquals("fixture_failure", one.call("fail", mapOf("kind" to "TECHNICAL")).errorCode())
        assertEquals("plugin_failed", two.call("fail", mapOf("kind" to "unexpected")).errorCode())
        assertEquals("plugin_user_circuit_open", one.call("report").errorCode())
        delay(300)
        assertEquals("v1", two.call("report")["version"].asText())
        assertEquals("v1", one.call("report")["version"].asText())
    }

    @Test
    fun `cancelled and timed out waiters never execute while another owner remains independent`(): Unit = runtimeTest {
        val first = install("kotlin-v1", 0)
        val local = runtime(config.copy(queueMillis = 200))
        local.reconcile()
        val pinned = bind(local, first)
        val other = bind(local, first, "bob")
        val held = requireNotNull(repository.claim("alice", first, config))
        try {
            coroutineScope {
                val waiting = async { pinned.call("state") }
                awaitPlugin { local.snapshot.status.single().versions.single().calls == 1 }
                waiting.cancelAndJoin()
            }
            assertEquals("plugin_queue_timeout", pinned.call("state").errorCode())
            assertEquals(1, other.call("state", owner = "bob")["count"].asInt())
        } finally { repository.release(held) }
        assertEquals(1, pinned.call("state")["count"].asInt())
    }

    @Test
    fun `version breaker isolates a replica and opens only after distinct user failures`(): Unit = runtimeTest {
        val first = install("kotlin-v1", 0)
        val timing = config.copy(userFailureThreshold = 20, versionFailureThreshold = 3, versionMinimumUsers = 2, cooldownMillis = 250)
        val local = runtime(timing)
        val alice = bind(local, first)
        val bob = bind(local, first, "bob")
        val carol = bind(local, first, "carol")
        val peer = bind(runtime(timing), first, "carol")
        repeat(2) { assertEquals("fixture_failure", alice.call("fail", mapOf("kind" to "TECHNICAL")).errorCode()) }
        assertEquals("fixture_failure", bob.call("fail", mapOf("kind" to "TECHNICAL"), "bob").errorCode())
        assertEquals("plugin_version_circuit_open", carol.call("report", owner = "carol").errorCode())
        assertEquals("v1", peer.call("report", owner = "carol")["version"].asText())
        delay(300)
        assertEquals("v1", carol.call("report", owner = "carol")["version"].asText())
        assertEquals("v1", alice.call("report")["version"].asText())
    }
}

private fun runtimeTest(block: suspend PluginRuntimeFixture.() -> Unit): Unit = runBlocking {
    val fixture = PluginRuntimeFixture()
    try { fixture.block() } finally { fixture.close() }
}

internal class PluginRuntimeFixture {
    val dataSource: HikariDataSource = PostgresDataSourceFactory.create(postgresAppConfig(newPostgresSchema("plugin_runtime")).postgres)
    val repository = PostgresPluginRepository(dataSource)
    val artifactConfig = pluginArtifactConfig()
    val artifacts = S3PluginArtifacts(artifactConfig)
    val config = PluginRuntimeConfig(queueMillis = 5_000, callMillis = 5_000, prepareMillis = 15_000, shutdownMillis = 5_000)
    private val http = PluginHttpClient()
    private val instances = mutableListOf<BackendPluginRuntime>()
    private val bindings = mutableListOf<PluginExecutionBinding>()

    init {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("insert into users(id) values ('alice'), ('bob'), ('carol')") }
        }
    }

    fun runtime(timing: PluginRuntimeConfig = config, random: Random = Random.Default): BackendPluginRuntime =
        BackendPluginRuntime(repository, artifacts, runtimeServices(repository, http), timing, random = random).also(instances::add)

    suspend fun install(name: String, revision: Long): PluginBinding = PluginArtifact.inspect(pluginFixture(name)).binding.also {
        artifacts.store(pluginFixture(name), it)
        repository.install(it, revision)
    }

    suspend fun bind(runtime: BackendPluginRuntime, binding: PluginBinding, owner: String = "alice"): PluginExecutionBinding =
        runtime.acquire(owner, listOf(binding), PLUGIN_FIXTURE_TOOLS).also(bindings::add)

    suspend fun close() {
        bindings.forEach { it.release() }
        instances.forEach { check(it.shutdown()) { "Plugin test failed to drain." } }
        http.close(); artifacts.close(); dataSource.close()
    }
}

internal val PLUGIN_FIXTURE_TOOLS = setOf("report", "state", "fetch", "fail", "block").map { "plugin.reference.$it" }.toSet()
internal fun runtimeServices(repository: PostgresPluginRepository, http: PluginHttpClient): PluginServices =
    PluginServices(repository, http, null, ChannelProviderRegistry(emptyList()),
        ToolInvocationRuntimeSandboxResolver { error("The fixture must not resolve a sandbox.") }, null)
internal suspend fun PluginExecutionBinding.call(name: String, arguments: Map<String, Any> = emptyMap(), owner: String = "alice"): JsonNode {
    val tool = catalog.toolsByCategory.values.flatMap { it.values }.single { it.fn.name == "plugin.reference.$name" }
    return restJsonMapper.readTree(tool.invoke(LLMResponse.FunctionCall(tool.fn.name, arguments), ToolInvocationMeta(owner)).content)
}
internal fun JsonNode.errorCode(): String = path("error").path("code").asText()
internal suspend fun awaitPlugin(predicate: () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(10) }
