package ru.souz.backend.plugins

import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.souz.backend.crypto.sha256Hex
import ru.souz.backend.storage.postgres.read
import ru.souz.backend.storage.postgres.write

class PluginRepositoryTest {
    @Test
    fun `operator writes compare revisions and rollback conflicting immutable versions`() = runtimeTest {
        val first = binding("mail", "v1")
        assertEquals(1L, repository.install(first, 0).revision)
        val results = coroutineScope {
            listOf("v2", "v3").map { version -> async {
                try { Result.success(repository.install(binding("mail", version), 1)) }
                catch (failure: PluginRegistryException) { Result.failure(failure) }
            } }.awaitAll()
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals("plugin_revision_conflict", (results.single { it.isFailure }.exceptionOrNull() as PluginRegistryException).code)
        val installed = repository.registry().single()
        assertEquals(2L, installed.revision)
        assertEquals(2, installed.versions.size)
        registryError("plugin_version_conflict") { repository.install(first.copy(digest = "other".sha256Hex()), 2) }
        assertEquals(installed, repository.registry().single())
        registryError("plugin_revision_conflict") { repository.install(binding("failed", "v1"), 3) }
        assertEquals(listOf("mail"), repository.registry().map { it.id })
        assertEquals(first.digest, repository.select("mail", "v1", 2).defaultDigest)
        registryError("plugin_version_not_found") { repository.select("mail", "missing", 3) }
        assertEquals(3L, repository.registry().single().revision)
        registryError("plugin_invalid_identity") { repository.install(first.copy(id = "../invalid"), 0) }
    }

    @Test
    fun `retirement preserves pins and quarantine survives repository restarts until explicit reset`() = runtimeTest {
        val old = binding("mail", "v1")
        val current = binding("mail", "v2")
        repository.install(old, 0)
        repository.install(current, 1)
        val retired = repository.retire("mail", "v1", 2)
        assertEquals(current.digest, retired.defaultDigest)
        assertTrue(retired.versions.single { it.binding == old }.retired)
        val pinned = assertNotNull(peer.claim("alice", old, timing))
        repository.state(pinned).put("kept", "true")
        repository.release(pinned)
        assertNull(repository.retire("mail", "v2", 3).defaultDigest)
        assertFalse(repository.select("mail", "v1", 4).versions.single { it.binding == old }.retired)
        repository.recordSevereIncident(old, "fault-1", 3, "plugin_call_stuck", "replica-one")
        peer.recordSevereIncident(old, "fault-1", 3, "plugin_cleanup_failed", "replica-two")
        coroutineScope {
            listOf(repository, peer).map { async { it.recordSevereIncident(old, "fault-2", 3) } }.awaitAll()
        }
        repository.recordSevereIncident(old, "fault-3", 3)
        peer.recordSevereIncident(old, "fault-3", 3)
        val broken = peer.registry().single()
        assertEquals(8L, broken.revision)
        assertEquals(3, broken.versions.single { it.binding == old }.severeIncidents)
        dataSource.read { connection -> connection.createStatement().use { statement ->
            statement.executeQuery("select reason_code, replica_id, recorded_at from jvm_plugin_incidents where incident_id = 'fault-1'").use {
                assertTrue(it.next())
                assertEquals("plugin_call_stuck", it.getString(1))
                assertEquals("replica-one", it.getString(2))
                assertNotNull(it.getTimestamp(3))
                assertFalse(it.next())
            }
        } }
        unavailable("plugin_quarantined") { peer.claim("alice", old, timing) }
        registryError("plugin_quarantined") { repository.select("mail", "v1", 8) }
        repository.reenable("mail", "v1", 8)
        repository.recordSevereIncident(old, "fault-3", 3)
        assertEquals(0, peer.version(old)?.severeIncidents)
        val admitted = assertNotNull(peer.claim("alice", old, timing))
        repository.disable("mail", 9)
        unavailable("plugin_disabled") { peer.claim("bob", current, timing) }
        assertTrue(repository.renew(admitted, timing.leaseMillis))
        repository.state(admitted).put("after-disable", "true")
        assertEquals("true", peer.state(admitted).get("kept"))
        repository.release(admitted)
        assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
    }

    @Test
    fun `whole invocations serialize across versions and replicas while owner and plugin state stay separate`() = runtimeTest {
        val one = binding("mail", "v1")
        val two = binding("mail", "v2")
        val other = binding("calendar", "v1")
        repository.install(one, 0)
        repository.install(two, 1)
        repository.install(other, 0)
        val alice = assertNotNull(repository.claim("alice", one, timing))
        assertNull(peer.claim("alice", two, timing))
        val bob = assertNotNull(peer.claim("bob", two, timing))
        val calendar = assertNotNull(peer.claim("alice", other, timing))
        repository.state(alice).put("same", "{\"owner\":\"alice\"}")
        assertNull(peer.state(bob).get("same"))
        assertNull(peer.state(calendar).get("same"))
        assertEquals(0, dataSource.hikariPoolMXBean.activeConnections)
        repository.release(alice)
        val winners = coroutineScope { List(2) { async { peer.claim("alice", two, timing) } }.awaitAll() }
        val resumed = winners.filterNotNull().single()
        assertEquals("{\"owner\":\"alice\"}", peer.state(resumed).get("same"))
        unavailable("plugin_lease_lost") { repository.state(alice).put("same", "false") }
        assertFalse(repository.renew(alice, timing.leaseMillis))
        repository.release(alice)
        repository.recordOutcome(alice, PluginCallOutcome.TECHNICAL_FAILURE, timing)
        peer.assertOwned(resumed)
        assertTrue(peer.state(resumed).delete("same"))
        assertFalse(peer.state(resumed).delete("same"))
        listOf(resumed, bob, calendar).forEach { repository.release(it) }
    }

    @Test
    fun `state and renewal check expiry after a contended row lock and stale release cannot clear replacement`() = runtimeTest {
        val artifact = binding("mail", "v1")
        repository.install(artifact, 0)
        val lease = assertNotNull(repository.claim("alice", artifact, timing))
        coroutineScope {
            dataSource.connection.use { held ->
                held.autoCommit = false
                held.prepareStatement("select * from jvm_plugin_calls where token = ? for update").use {
                    it.setObject(1, lease.token); it.executeQuery().close()
                }
                val stale = async { assertFailsWith<PluginUnavailableException> { peer.state(lease).put("late", "true") } }
                awaitBlockedQuery()
                held.expire(lease.token)
                held.commit()
                assertEquals("plugin_lease_lost", stale.await().code)
            }
        }
        assertFalse(repository.renew(lease, timing.leaseMillis))
        val replacement = assertNotNull(peer.claim("alice", artifact, timing))
        assertNull(repository.state(replacement).get("late"))
        repository.release(lease)
        peer.assertOwned(replacement)
        peer.release(replacement)
    }

    @Test
    fun `distributed user breaker records each outcome once and allows one recovery probe`() = runtimeTest {
        val artifact = binding("mail", "v1")
        repository.install(artifact, 0)
        val breaker = timing.copy(userFailureThreshold = 2)
        repeat(2) {
            val lease = assertNotNull(repository.claim("alice", artifact, breaker))
            repository.recordOutcome(lease, PluginCallOutcome.TECHNICAL_FAILURE, breaker)
            peer.recordOutcome(lease, PluginCallOutcome.SUCCESS, breaker)
            repository.release(lease)
        }
        unavailable("plugin_user_circuit_open") { peer.claim("alice", artifact, breaker) }
        val independent = assertNotNull(repository.claim("bob", artifact, breaker))
        repository.release(independent)
        expireBreaker()
        val probes = coroutineScope { List(2) { async { peer.claim("alice", artifact, breaker) } }.awaitAll() }
        val probe = probes.filterNotNull().single()
        repository.recordOutcome(probe, PluginCallOutcome.TECHNICAL_FAILURE, breaker)
        repository.release(probe)
        unavailable("plugin_user_circuit_open") { repository.claim("alice", artifact, breaker) }
        expireBreaker()
        val recovery = assertNotNull(peer.claim("alice", artifact, breaker))
        peer.recordOutcome(recovery, PluginCallOutcome.SUCCESS, breaker)
        peer.release(recovery)
        val ordinary = assertNotNull(repository.claim("alice", artifact, breaker))
        repository.recordOutcome(ordinary, PluginCallOutcome.IGNORED, breaker)
        repository.release(ordinary)
        val next = assertNotNull(peer.claim("alice", artifact, breaker))
        repository.recordOutcome(next, PluginCallOutcome.TECHNICAL_FAILURE, breaker)
        repository.release(next)
        val belowThreshold = assertNotNull(peer.claim("alice", artifact, breaker))
        repository.release(belowThreshold)
    }

    @Test
    fun `state bounds cursor order and user deletion apply under invocation ownership`() = runtimeTest {
        val artifact = binding("mail", "v1")
        repository.install(artifact, 0)
        val lease = assertNotNull(repository.claim("alice", artifact, timing))
        val state = repository.state(lease)
        val ordered = listOf("A", "a", "é", "😀")
        ordered.forEach { state.put(it, "null") }
        val first = state.list(limit = 2)
        assertEquals(ordered.take(2), first.records.map { it.key })
        assertEquals("a", first.nextCursor)
        assertEquals(ordered.drop(2), state.list(first.nextCursor, 2).records.map { it.key })
        assertNull(state.list(first.nextCursor, 2).nextCursor)
        assertEquals("null", state.get("a"))
        val exactBound = "\"${"a".repeat(1_048_574)}\""
        state.put("exact-bound", exactBound)
        assertEquals(exactBound, state.get("exact-bound"))
        assertFailsWith<IllegalArgumentException> { state.put("large", "\"${"a".repeat(1_048_575)}\"") }
        assertFailsWith<IllegalArgumentException> { state.get("a".repeat(513)) }
        assertFailsWith<IllegalArgumentException> { state.get("\u0000") }
        assertFailsWith<Exception> { state.put("invalid", "{} {}") }
        assertFailsWith<IllegalArgumentException> { state.list(limit = 101) }
        dataSource.write { it.createStatement().use { query -> query.executeUpdate("delete from users where id = 'alice'") } }
        unavailable("plugin_lease_lost") { state.get("a") }
        assertEquals(1, repository.registry().size)
        assertEquals(0, dataSource.read { connection ->
            connection.createStatement().use { it.executeQuery("select count(*) from jvm_plugin_state").use { row -> row.next(); row.getInt(1) } }
        })
    }
}

private fun binding(id: String, version: String) = PluginBinding(id, version, "$id:$version".sha256Hex())
private fun Connection.expire(token: UUID) = prepareStatement(
    "update jvm_plugin_calls set lease_until = clock_timestamp() - interval '1 second' where token = ?",
).use { it.setObject(1, token); it.executeUpdate() }
private suspend fun registryError(code: String, block: suspend () -> Unit) {
    assertEquals(code, assertFailsWith<PluginRegistryException> { block() }.code)
}
private suspend fun unavailable(code: String, block: suspend () -> Unit) {
    assertEquals(code, assertFailsWith<PluginUnavailableException> { block() }.code)
}
