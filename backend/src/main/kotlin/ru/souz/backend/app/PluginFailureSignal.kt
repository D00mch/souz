package ru.souz.backend.app

import kotlinx.coroutines.CompletableDeferred

/** The runtime reports failure; only the process entry point owns terminating the JVM. */
internal class PluginFailureSignal {
    private val failure = CompletableDeferred<Unit>()
    val requested: Boolean get() = failure.isCompleted
    fun requestRestart() { failure.complete(Unit) }
    suspend fun await() = failure.await()
}

/** JVM shutdown hooks can themselves stall. This platform-thread boundary guarantees process exit. */
internal suspend fun exitAfterPluginFailure(
    drainMillis: Long,
    stopHttpIntake: () -> Unit,
    shutdownRuntime: suspend () -> Unit,
) {
    Thread.ofPlatform().daemon(true).name("souz-plugin-exit-watchdog").start {
        Thread.sleep(drainMillis)
        Runtime.getRuntime().halt(1)
    }
    try { shutdownBackendProcess(stopHttpIntake, shutdownRuntime) }
    finally { Runtime.getRuntime().halt(1) }
}
