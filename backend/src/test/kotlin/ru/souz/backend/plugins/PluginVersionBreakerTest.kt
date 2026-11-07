package ru.souz.backend.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginVersionBreakerTest {
    @Test fun `admission prunes expired owner history without waiting for another outcome`() {
        val breaker = PluginVersionBreaker(config.copy(versionFailureThreshold = 100_000))
        repeat(1_000) { breaker.record(assertNotNull(breaker.admit(1)), "owner-$it", PluginCallOutcome.TECHNICAL_FAILURE, 1) }
        assertNotNull(breaker.admit(101))
        val field = PluginVersionBreaker::class.java.getDeclaredField("failures").apply { isAccessible = true }
        assertTrue((field.get(breaker) as Collection<*>).isEmpty())
    }

    private val config = PluginRuntimeConfig(versionFailureThreshold = 3, versionMinimumUsers = 2,
        versionWindowMillis = 100, cooldownMillis = 20)

    @Test
    fun `one user's technical failures do not disable other users and old failures expire`() {
        val breaker = PluginVersionBreaker(config)
        repeat(5) { breaker.record(assertNotNull(breaker.admit(it.toLong())), "alice", PluginCallOutcome.TECHNICAL_FAILURE, it.toLong()) }
        assertFalse(breaker.isOpen(5))
        breaker.record(assertNotNull(breaker.admit(110)), "bob", PluginCallOutcome.TECHNICAL_FAILURE, 110)
        assertFalse(breaker.isOpen(110))
        breaker.record(assertNotNull(breaker.admit(111)), "alice", PluginCallOutcome.TECHNICAL_FAILURE, 111)
        breaker.record(assertNotNull(breaker.admit(112)), "bob", PluginCallOutcome.TECHNICAL_FAILURE, 112)
        assertTrue(breaker.isOpen(112))
        assertNull(breaker.admit(131))
    }

    @Test
    fun `cooldown admits one ordinary probe and technical failures reopen while success closes`() {
        val breaker = PluginVersionBreaker(config)
        listOf("alice", "bob", "alice").forEachIndexed { time, owner ->
            breaker.record(assertNotNull(breaker.admit(time.toLong())), owner, PluginCallOutcome.TECHNICAL_FAILURE, time.toLong())
        }
        val probe = assertNotNull(breaker.admit(22))
        assertTrue(probe.probe)
        assertNull(breaker.admit(23))
        breaker.record(probe, "bob", PluginCallOutcome.TECHNICAL_FAILURE, 24)
        assertNull(breaker.admit(43))
        val recovered = assertNotNull(breaker.admit(44))
        breaker.record(recovered, "bob", PluginCallOutcome.SUCCESS, 45)
        assertFalse(assertNotNull(breaker.admit(45)).probe)
        repeat(10) {
            breaker.record(assertNotNull(breaker.admit(46)), "alice", PluginCallOutcome.IGNORED, 46)
        }
        assertFalse(breaker.isOpen(46))
    }
}
