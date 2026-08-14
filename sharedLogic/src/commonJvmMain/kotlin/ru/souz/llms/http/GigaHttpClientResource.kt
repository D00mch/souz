package ru.souz.llms.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.util.concurrent.atomic.AtomicBoolean
import ru.souz.llms.giga.gigaTlsDefaults

/** Separate process-owned transport for Giga services and their custom trust roots. */
class GigaHttpClientResource(
    val client: HttpClient = createGigaHttpClient(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            client.close()
        }
    }
}

fun createGigaHttpClient(): HttpClient =
    HttpClient(CIO) {
        providerHttpClientDefaults()
        gigaTlsDefaults()
    }
