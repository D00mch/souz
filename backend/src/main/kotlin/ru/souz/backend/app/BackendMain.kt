package ru.souz.backend.app

import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import org.slf4j.LoggerFactory
import ru.souz.backend.http.BackendHttpServer

private val log = LoggerFactory.getLogger("SouzBackend")

/** Starts the embedded Souz backend HTTP server. */
fun main() {
    val appConfig = BackendAppConfig.load().validate()
    if (appConfig.server.proxyToken.isNullOrBlank()) {
        log.warn("SOUZ_BACKEND_PROXY_TOKEN is not configured; /v1 routes will reject all requests.")
    }

    val runtime = BackendRuntime.create(appConfig)
    val server = BackendHttpServer(
        dependencies = runtime.httpDependencies,
        bindAddress = InetSocketAddress(appConfig.server.host, appConfig.server.port),
    )
    val shutdown = Runnable {
        runCatching { server.close() }
            .onFailure { log.warn("Failed to stop backend server: {}", it.message) }
        runCatching { runtime.close() }
            .onFailure { log.warn("Failed to close backend runtime: {}", it.message) }
    }
    Runtime.getRuntime().addShutdownHook(Thread(shutdown, "souz-backend-shutdown"))

    runtime.startBackgroundServices()
    server.start()
    log.info(
        "Bootstrap API: GET http://{}:{}/v1/bootstrap",
        appConfig.server.host,
        appConfig.server.port,
    )
    CountDownLatch(1).await()
}
