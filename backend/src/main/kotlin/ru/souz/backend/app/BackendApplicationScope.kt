package ru.souz.backend.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/** Process-wide coroutine scope for backend background work. */
class BackendApplicationScope(
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default,
) : CoroutineScope, AutoCloseable {
    override fun close() {
        coroutineContext.cancel()
    }
}
