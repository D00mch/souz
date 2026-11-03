package ru.souz.backend.plugins

/** Replica-local technical-failure circuit. All access belongs to the runtime state mutex. */
internal class PluginVersionBreaker(private val config: PluginRuntimeConfig) {
    private val failures = ArrayDeque<Pair<Long, String>>()
    private var openUntil = 0L
    private var probe: Permit? = null

    class Permit internal constructor(val probe: Boolean)

    fun admit(now: Long): Permit? {
        if (now < openUntil || probe != null) return null
        return Permit(openUntil != 0L).also { if (it.probe) probe = it }
    }

    fun record(permit: Permit, owner: String, outcome: PluginCallOutcome, now: Long) {
        prune(now)
        if (permit.probe && probe === permit) {
            probe = null
            when (outcome) {
                PluginCallOutcome.SUCCESS -> { openUntil = 0; failures.clear() }
                PluginCallOutcome.TECHNICAL_FAILURE -> openUntil = now + config.cooldownMillis
                PluginCallOutcome.IGNORED -> Unit
            }
            return
        }
        if (outcome != PluginCallOutcome.TECHNICAL_FAILURE) return
        failures += now to owner
        if (failures.size >= config.versionFailureThreshold &&
            failures.map { it.second }.distinct().size >= config.versionMinimumUsers) {
            openUntil = now + config.cooldownMillis
        }
    }

    fun isOpen(now: Long): Boolean = now < openUntil || probe != null

    private fun prune(now: Long) {
        while (failures.firstOrNull()?.let { now - it.first >= config.versionWindowMillis } == true) failures.removeFirst()
    }
}
