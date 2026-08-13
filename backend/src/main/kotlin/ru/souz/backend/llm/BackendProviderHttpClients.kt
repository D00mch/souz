package ru.souz.backend.llm

import io.ktor.client.HttpClient
import ru.souz.llms.LlmProvider
import ru.souz.llms.createProviderHttpClient

/** Owns the backend's application-scoped provider HTTP clients and connection pools. */
class BackendProviderHttpClients(
    createHttpClient: (LlmProvider) -> HttpClient = ::createProviderHttpClient,
) : AutoCloseable {
    private val clients = LlmProvider.entries
        .filterNot { it == LlmProvider.LOCAL }
        .associateWith(createHttpClient)
    private var closed = false

    fun clientFor(provider: LlmProvider): HttpClient {
        check(!closed) { "Provider HTTP clients are closed." }
        return clients[provider] ?: error("Provider $provider does not use an HTTP client.")
    }

    override fun close() {
        if (closed) return
        closed = true
        clients.values.forEach(HttpClient::close)
    }
}
