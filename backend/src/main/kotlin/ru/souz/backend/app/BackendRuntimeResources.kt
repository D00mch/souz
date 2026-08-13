package ru.souz.backend.app

import kotlinx.coroutines.runBlocking
import ru.souz.runtime.OrderedShutdown
import ru.souz.runtime.shutdownStep

class BackendRuntimeResources(
    cancelAndJoinApplicationWork: suspend () -> Unit = {},
    closeProviderClients: () -> Unit = {},
    closeLocalRuntime: () -> Unit = {},
    closeDataSource: () -> Unit = {},
) : AutoCloseable {
    private val shutdown = OrderedShutdown(
        steps = listOf(
            shutdownStep("application work", cancelAndJoinApplicationWork),
            shutdownStep("provider HTTP clients") { closeProviderClients() },
            shutdownStep("local runtime") { closeLocalRuntime() },
            shutdownStep("database") { closeDataSource() },
        )
    )

    suspend fun shutdown() = shutdown.shutdown()

    override fun close() {
        runBlocking { shutdown() }
    }
}
