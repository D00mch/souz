package ru.souz

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import ru.souz.llms.http.GigaHttpClientResource
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.local.LocalLlamaRuntime
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
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        logger.info("Shutting down desktop process resources")
        closeResource("application scope") { applicationScope.cancel() }
        closeResource("local runtime") { localLlamaRuntime.close() }
        closeResource("MCP manager") { mcpClientManager.close() }
        telegramBotController?.let { controller ->
            closeResource("Telegram bot controller") { controller.close() }
        }
        closeResource("provider HTTP clients") { providerHttpClients.close() }
        closeResource("Giga HTTP client") { gigaHttpClientResource.close() }
        closeResource("shutdown observer", afterClose)
    }

    private fun closeResource(name: String, close: () -> Unit) {
        runCatching(close)
            .onFailure { failure -> logger.warn("Failed to close {}: {}", name, failure.message) }
    }
}
