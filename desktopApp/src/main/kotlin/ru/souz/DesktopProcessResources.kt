package ru.souz

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import ru.souz.llms.http.GigaHttpClientResource
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.local.LocalLlamaRuntime
import ru.souz.runtime.OrderedShutdown
import ru.souz.runtime.shutdownStep
import ru.souz.service.mcp.McpClientManager
import ru.souz.service.telegram.TelegramBotController

/** Owns process-lifetime desktop resources shared by the UI and text entry points. */
internal class DesktopProcessResources(
    private val applicationScope: CoroutineScope,
    private val localLlamaRuntime: LocalLlamaRuntime,
    private val mcpClientManager: McpClientManager,
    private val providerHttpClients: ProviderHttpClients,
    private val gigaHttpClientResource: GigaHttpClientResource,
    private val telegramBotController: TelegramBotController? = null,
    private val afterClose: () -> Unit = {},
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(DesktopProcessResources::class.java)
    private val shutdown = OrderedShutdown(
        steps = buildList {
            add(shutdownStep("application scope") { applicationScope.coroutineContext[Job]?.cancelAndJoin() })
            add(shutdownStep("local runtime") { localLlamaRuntime.close() })
            add(shutdownStep("MCP manager") { mcpClientManager.close() })
            telegramBotController?.let { controller ->
                add(shutdownStep("Telegram bot controller") { controller.close() })
            }
            add(shutdownStep("provider HTTP clients") { providerHttpClients.close() })
            add(shutdownStep("Giga HTTP client") { gigaHttpClientResource.close() })
            add(shutdownStep("shutdown observer") { afterClose() })
        },
        beforeShutdown = {
            logger.info("Shutting down desktop process resources")
        },
        onStepFailure = { step, failure ->
            logger.warn("Failed to close {}: {}", step.name, failure.message)
        },
    )

    suspend fun shutdown() = shutdown.shutdown()

    override fun close() {
        runBlocking { shutdown() }
    }
}
