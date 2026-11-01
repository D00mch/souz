package ru.souz.backend.app

import kotlinx.coroutines.runBlocking
import ru.souz.runtime.OrderedShutdown
import ru.souz.runtime.shutdownStep

class BackendRuntimeResources(
    cancelAndJoinApplicationWork: suspend () -> Unit = {},
    closeProviderClients: () -> Unit = {},
    closeSkillOAuthClients: () -> Unit = {},
    closeDataSource: () -> Unit = {},
    closeAttachmentStorage: () -> Unit = {},
    closePlugins: suspend () -> Unit = {},
) : AutoCloseable {
    private val shutdown = OrderedShutdown(
        steps = listOf(
            shutdownStep("application work", cancelAndJoinApplicationWork),
            shutdownStep("JVM Skill plugins", closePlugins),
            shutdownStep("provider HTTP clients") { closeProviderClients() },
            shutdownStep("skill OAuth clients") { closeSkillOAuthClients() },
            shutdownStep("attachment storage") { closeAttachmentStorage() },
            shutdownStep("database") { closeDataSource() },
        )
    )

    suspend fun shutdown() = shutdown.shutdown()

    override fun close() {
        runBlocking { shutdown() }
    }
}
