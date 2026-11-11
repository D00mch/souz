package ru.souz.backend.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import ru.souz.backend.storage.postgres.read
import ru.souz.backend.storage.postgres.write
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

class PluginInvocationLeaseTest {
    @Test
    fun `worker-queued invocation renews its lease and excludes another replica`() = queuedLeaseInvocation(loseOwnership = false)

    @Test
    fun `worker-queued invocation checks ownership before handler entry and cannot release a successor`() = queuedLeaseInvocation(loseOwnership = true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun queuedLeaseInvocation(loseOwnership: Boolean): Unit = runtimeTest {
        val pin = install("kotlin-v1", 0)
        // Ownership loss must be caught at entry, before the next scheduled renewal.
        val timing = if (loseOwnership) config.copy(leaseMillis = 90_000, renewMillis = 60_000)
            else config.copy(leaseMillis = 1_000, renewMillis = 100)
        val worker = StandardTestDispatcher()
        val local = runtime(timing, workDispatcher = worker)
        local.reconcile()
        awaitPlugin { worker.scheduler.runCurrent(); local.snapshot.defaultBindings == listOf(pin) }
        val binding = bind(local, pin)
        val version = binding.versions.single()
        val entered = CompletableDeferred<Unit>()
        val tool = version.functions.single { it.schema.name.endsWith(".report") }.copy(handler = { _, context ->
            entered.complete(Unit)
            if (!loseOwnership) context.state.put("queued", "{}")
            "{}"
        })
        try {
            coroutineScope {
                val pending = async { local.invoke(binding, version, tool, LLMResponse.FunctionCall(tool.schema.name, emptyMap()), ToolInvocationMeta("alice")) }
                awaitPlugin { local.snapshot.status.single().versions.single().calls == 1 }
                val successor = if (loseOwnership) {
                    dataSource.write { db -> db.createStatement().use {
                        it.executeUpdate("update jvm_plugin_calls set lease_until = clock_timestamp() - interval '1 second' where owner_id = 'alice'")
                    } }
                    assertNotNull(peer.claim("alice", pin, config))
                } else {
                    suspend fun leaseUntil() = dataSource.read { db -> db.createStatement().use { statement ->
                        statement.executeQuery("select lease_until from jvm_plugin_calls where owner_id = 'alice'").use {
                            check(it.next()); it.getTimestamp(1).toInstant().toEpochMilli()
                        }
                    } }
                    val original = leaseUntil()
                    // Observe renewal beyond an entire lease lifetime while no worker is allowed to run.
                    withTimeout(3_000) { while (leaseUntil() <= original + timing.leaseMillis) delay(10) }
                    assertFalse(pending.isCompleted)
                    assertNull(peer.claim("alice", pin, config))
                    null
                }
                assertFalse(entered.isCompleted)
                try {
                    awaitPlugin { worker.scheduler.runCurrent(); pending.isCompleted && local.snapshot.status.single().versions.single().calls == 0 }
                    val response = pending.await()
                    if (successor != null) {
                        assertEquals("plugin_lease_lost", restJsonMapper.readTree(response.content).errorCode())
                        assertFalse(entered.isCompleted, "A stale lease must not enter even a handler that uses no host services.")
                        peer.assertOwned(successor)
                    } else {
                        assertEquals("{}", response.content)
                        assertTrue(entered.isCompleted)
                    }
                } finally {
                    successor?.let { peer.release(it) }
                }
            }
        } finally {
            binding.release()
            coroutineScope {
                val shutdown = async { local.shutdown() }
                awaitPlugin { worker.scheduler.runCurrent(); shutdown.isCompleted }
                assertTrue(shutdown.await())
            }
        }
    }
}
