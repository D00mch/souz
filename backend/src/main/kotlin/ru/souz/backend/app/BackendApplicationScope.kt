package ru.souz.backend.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

/** Process-wide coroutine scope for backend background work. */
class BackendApplicationScope(
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default,
) : CoroutineScope, AutoCloseable {
    suspend fun cancelAndJoin() {
        coroutineContext[Job]?.cancelAndJoin()
    }

    override fun close() {
        runBlocking { cancelAndJoin() }
    }
}
