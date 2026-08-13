package ru.souz

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val shutdownMutex = Mutex()
    private var shutdownCompletion: CompletableDeferred<Result<Unit>>? = null

    suspend fun shutdown() {
        val (completion, ownsShutdown) = shutdownMutex.withLock {
            val existing = shutdownCompletion
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<Result<Unit>>()
                    .also { shutdownCompletion = it } to true
            }
        }
        if (!ownsShutdown) {
            completion.await().getOrThrow()
            return
        }

        val result = withContext(NonCancellable) {
            logger.info("Shutting down desktop process resources")
            closeInOrder().also(completion::complete)
        }
        result.getOrThrow()
    }

    override fun close() {
        runBlocking { shutdown() }
    }

    private suspend fun closeInOrder(): Result<Unit> {
        var failure: Throwable? = null

        suspend fun runStep(name: String, step: suspend () -> Unit) {
            try {
                step()
            } catch (stepFailure: Throwable) {
                logger.warn("Failed to close {}: {}", name, stepFailure.message)
                if (failure == null) {
                    failure = stepFailure
                } else {
                    failure.addSuppressed(stepFailure)
                }
            }
        }

        runStep("application scope") { applicationScope.coroutineContext[Job]?.cancelAndJoin() }
        runStep("local runtime") { localLlamaRuntime.close() }
        runStep("MCP manager") { mcpClientManager.close() }
        telegramBotController?.let { controller ->
            runStep("Telegram bot controller") { controller.close() }
        }
        runStep("provider HTTP clients") { providerHttpClients.close() }
        runStep("Giga HTTP client") { gigaHttpClientResource.close() }
        runStep("shutdown observer") { afterClose() }

        return failure?.let(Result.Companion::failure) ?: Result.success(Unit)
    }
}
