package ru.souz.backend.app

import kotlinx.coroutines.runBlocking
import ru.souz.runtime.OrderedShutdown
import ru.souz.runtime.shutdownStep

class BackendRuntimeResources(
    cancelAndJoinApplicationWork: suspend () -> Unit = {},
    closeProviderClients: () -> Unit = {},
    closeLocalRuntime: () -> Unit = {},
    closeSkillOAuthClients: () -> Unit = {},
    closeDataSource: () -> Unit = {},
    closeAttachmentStorage: () -> Unit = {},
    shutdownPlugins: suspend () -> Boolean = { true },
    pluginDidNotDrain: () -> Unit = {},
    closePluginServices: () -> Unit = {},
) : AutoCloseable {
    private var pluginsDrained = false
    private val shutdown = OrderedShutdown(
        steps = listOf(
            shutdownStep("application work", cancelAndJoinApplicationWork),
            shutdownStep("plugins") {
                try { pluginsDrained = shutdownPlugins() }
                finally { if (!pluginsDrained) pluginDidNotDrain() }
            },
            shutdownStep("plugin services") { if (pluginsDrained) closePluginServices() },
            shutdownStep("provider HTTP clients") { if (pluginsDrained) closeProviderClients() },
            shutdownStep("local runtime") { if (pluginsDrained) closeLocalRuntime() },
            shutdownStep("skill OAuth clients") { if (pluginsDrained) closeSkillOAuthClients() },
            shutdownStep("attachment storage") { if (pluginsDrained) closeAttachmentStorage() },
            shutdownStep("database") { if (pluginsDrained) closeDataSource() },
        )
    )

    suspend fun shutdown() = shutdown.shutdown()

    override fun close() {
        runBlocking { shutdown() }
    }
}
