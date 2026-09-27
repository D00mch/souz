package ru.souz.backend.app

import kotlinx.coroutines.runBlocking
import ru.souz.runtime.OrderedShutdown
import ru.souz.runtime.shutdownStep

class BackendRuntimeResources(
    releaseClientAcknowledgements: suspend () -> Unit = {},
    cancelAndJoinApplicationWork: suspend () -> Unit = {},
    closeProviderClients: () -> Unit = {},
    closeLocalRuntime: () -> Unit = {},
    closeSkillOAuthClients: () -> Unit = {},
    closeDataSource: () -> Unit = {},
) : AutoCloseable {
    private val shutdown = OrderedShutdown(
        steps = listOf(
            shutdownStep("client acknowledgements", releaseClientAcknowledgements),
            shutdownStep("application work", cancelAndJoinApplicationWork),
            shutdownStep("provider HTTP clients") { closeProviderClients() },
            shutdownStep("local runtime") { closeLocalRuntime() },
            shutdownStep("skill OAuth clients") { closeSkillOAuthClients() },
            shutdownStep("database") { closeDataSource() },
        )
    )

    suspend fun shutdown() = shutdown.shutdown()

    override fun close() {
        runBlocking { shutdown() }
    }
}
