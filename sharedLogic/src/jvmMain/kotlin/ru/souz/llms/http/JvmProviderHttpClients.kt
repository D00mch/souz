package ru.souz.llms.http

import io.ktor.client.HttpClient
import java.util.concurrent.atomic.AtomicBoolean
import ru.souz.mcp.createMcpHttpClient

/** Process-owned HTTP clients shared by provider adapters. */
class ProviderHttpClients(
    val standard: HttpClient,
    val openAi: HttpClient,
    val jev: HttpClient = standard,
    val mcp: HttpClient,
) : AutoCloseable {
    constructor() : this(createProviderHttpClients())

    private constructor(clients: List<HttpClient>) : this(clients[0], clients[1], clients[2], clients[3])

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        for (client in listOf(standard, openAi, jev, mcp).distinct()) {
            try {
                client.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) {
                    failure = closeFailure
                } else {
                    failure.addSuppressed(closeFailure)
                }
            }
        }
        failure?.let { throw it }
    }
}

private fun createProviderHttpClients(): List<HttpClient> {
    val clients = mutableListOf<HttpClient>()
    try {
        clients += createStandardProviderHttpClient()
        clients += createOpenAiProviderHttpClient()
        clients += createOkHttpProviderHttpClient()
        clients += createMcpHttpClient()
        return clients
    } catch (failure: Throwable) {
        clients.forEach { client ->
            runCatching { client.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        }
        throw failure
    }
}
