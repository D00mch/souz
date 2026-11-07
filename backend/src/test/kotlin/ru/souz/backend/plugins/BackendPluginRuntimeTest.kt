package ru.souz.backend.plugins

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.jupiter.api.io.TempDir

class BackendPluginRuntimeTest {
    @TempDir lateinit var directory: Path

    @Test fun `concurrent and repeated raw shutdown shares one filesystem cleanup`(): Unit = runtimeTest {
        val pin = install("kotlin-v1", 0)
        repeat(4) {
            val local = runtime()
            local.reconcile()
            awaitPlugin { local.snapshot.defaultBindings == listOf(pin) }
            val results = coroutineScope { List(8) { async { local.shutdown() } }.awaitAll() }
            assertTrue(results.all { it })
            assertTrue(local.shutdown())
        }
    }

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
        assertEquals(setOf("v1", "v2"), local.snapshot.status.single().versions.map { it.binding.version }.toSet())
        assertEquals("v1", pinned.call("report")["version"].asText())
        repository.retire("reference", "v1", 2)
        local.reconcile()
        assertEquals(2, local.snapshot.status.single().versions.count { it.phase != "failed" })
        pinned.release()
        awaitPlugin { local.snapshot.status.single().versions.map { it.binding.version } == listOf("v2") }
        val restored = bind(local, first)
        assertEquals("v1", restored.call("report")["version"].asText())
        restored.release()
        awaitPlugin { local.snapshot.status.single().versions.count { it.phase != "failed" } == 1 }
        repository.retire("reference", "v2", 3)
        local.reconcile()
        awaitPlugin { local.snapshot.status.single().versions.count { it.phase != "failed" } == 0 }
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
        val restarted = runtime()
        restarted.reconcile()
        assertTrue(restarted.snapshot.status.single().versions.isEmpty())
        assertTrue(restarted.snapshot.defaultBindings.isEmpty())
        assertEquals("plugin_disabled", bind(restarted, first).call("report").errorCode())
    }

    @Test
    fun `failed activation preserves eligible applied default and retains diagnostic after cleanup`(): Unit = runtimeTest {
        val first = install("kotlin-v1", 0)
        val local = runtime(config.copy(retryMillis = 5_000))
        local.reconcile()
        awaitPlugin { local.snapshot.defaultBindings == listOf(first) }
        val broken = repackagePlugin(pluginFixture("kotlin-v2"), directory.resolve("broken.jar"),
            resources = mapOf("fixture-construction-fail" to byteArrayOf()))
        val unavailable = inspectPlugin(broken)
        artifacts.store(broken, unavailable)
        repository.install(unavailable, 1)
        local.reconcile()
        awaitPlugin { local.snapshot.status.single().versions.any { it.binding == unavailable && it.phase == "failed" && it.error != null } }
        assertEquals(listOf(first), local.snapshot.defaultBindings)
        val missing = unavailable.copy(version = "missing", digest = "0".repeat(64))
        repository.install(missing, 2)
        local.reconcile()
        awaitPlugin { local.snapshot.status.single().versions.any { it.binding == missing && it.phase == "failed" && it.error != null } }
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
        val settings = config.copy(userFailureThreshold = 3, cooldownMillis = 250)
        val one = bind(runtime(settings), first)
        val two = bind(runtime(settings), first)
        repeat(3) { assertEquals("fixture_failure", one.call("fail", mapOf("kind" to "BUSINESS")).errorCode()) }
        assertEquals("fixture_failure", one.call("fail", mapOf("kind" to "TECHNICAL")).errorCode())
        assertEquals("plugin_failed", two.call("fail", mapOf("kind" to "unexpected")).errorCode())
        val assertion = one.call("fail", mapOf("kind" to "assertion"))
        assertEquals("plugin_failed", assertion.errorCode())
        assertTrue("Private assertion data" !in assertion.toString())
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
                delay(50)
                assertEquals(0, local.snapshot.status.single().versions.single().calls)
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
    @Test
    fun `version window expires old failures and one ordinary probe settles recovery`() = runtimeTest {
        val pin = install("kotlin-v1", 0)
        val host = runtime(config.copy(userFailureThreshold = 20, versionFailureThreshold = 3,
            versionMinimumUsers = 2, versionWindowMillis = 400, cooldownMillis = 100))
        host.reconcile()
        val alice = bind(host, pin)
        val bob = bind(host, pin, "bob")
        repeat(5) { assertEquals("fixture_failure", alice.call("fail", mapOf("kind" to "TECHNICAL")).errorCode()) }
        assertEquals("v1", bob.call("report", owner = "bob")["version"].asText())
        delay(450)
        assertEquals("fixture_failure", bob.call("fail", mapOf("kind" to "TECHNICAL"), "bob").errorCode())
        assertEquals("v1", alice.call("report")["version"].asText())
        listOf(alice to "alice", bob to "bob").forEach { (binding, owner) ->
            assertEquals("fixture_failure", binding.call("fail", mapOf("kind" to "TECHNICAL"), owner).errorCode())
        }
        assertEquals("plugin_version_circuit_open", alice.call("report").errorCode())
        delay(150)
        assertEquals("fixture_failure", alice.call("fail", mapOf("kind" to "TECHNICAL")).errorCode())
        assertEquals("plugin_version_circuit_open", bob.call("report", owner = "bob").errorCode())
        delay(150)
        assertEquals("fixture_failure", alice.call("fail", mapOf("kind" to "BUSINESS")).errorCode())
        awaitPlugin { host.snapshot.status.single().versions.single().calls == 0 }
        coroutineScope {
            val probe = async { alice.call("state", mapOf("wait" to 500)) }
            awaitPlugin { host.snapshot.status.single().versions.single().calls == 1 }
            assertEquals("plugin_version_circuit_open", bob.call("report", owner = "bob").errorCode())
            assertEquals(1, probe.await()["count"].asInt())
        }
        assertEquals("v1", bob.call("report", owner = "bob")["version"].asText())
    }

}
