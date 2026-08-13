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
    val server = try {
        BackendHttpServer(
            dependencies = runtime.httpDependencies,
            bindAddress = InetSocketAddress(appConfig.server.host, appConfig.server.port),
        )
    } catch (startupFailure: Throwable) {
        try {
            runtime.close()
        } catch (closeFailure: Throwable) {
            if (closeFailure !== startupFailure) {
                startupFailure.addSuppressed(closeFailure)
            }
        }
        throw startupFailure
    }
    val shutdown = Runnable {
        var firstFailure: Throwable? = null
        try {
            server.close()
        } catch (closeFailure: Throwable) {
            log.warn("Failed to stop backend server: {}", closeFailure.message)
            firstFailure = closeFailure
        }
        try {
            runtime.close()
        } catch (closeFailure: Throwable) {
            log.warn("Failed to close backend runtime: {}", closeFailure.message)
            if (firstFailure == null) {
                firstFailure = closeFailure
            } else if (closeFailure !== firstFailure) {
                firstFailure.addSuppressed(closeFailure)
            }
        }
        firstFailure?.let { throw it }
    }
    Runtime.getRuntime().addShutdownHook(Thread(shutdown, "souz-backend-shutdown"))

    try {
        runtime.startBackgroundServices()
        server.start()
    } catch (startupFailure: Throwable) {
        try {
            shutdown.run()
        } catch (closeFailure: Throwable) {
            if (closeFailure !== startupFailure) {
                startupFailure.addSuppressed(closeFailure)
            }
        }
        throw startupFailure
    }
    log.info(
        "Bootstrap API: GET http://{}:{}/v1/bootstrap",
        appConfig.server.host,
        appConfig.server.port,
    )
    CountDownLatch(1).await()
}
