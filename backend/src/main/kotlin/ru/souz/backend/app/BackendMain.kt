package ru.souz.backend.app

import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import org.slf4j.LoggerFactory
import ru.souz.backend.http.BackendHttpServer

private val log = LoggerFactory.getLogger("SouzBackend")

/** Starts the embedded Souz backend HTTP server. */
fun main() {
    val host = configValue(envKey = "SOUZ_BACKEND_HOST", propertyKey = "souz.backend.host")
        ?: "127.0.0.1"
    val port = configValue(envKey = "SOUZ_BACKEND_PORT", propertyKey = "souz.backend.port")
        ?.toIntOrNull()
        ?: 8080
    val appConfig = BackendAppConfig.load().validate()
    if (appConfig.proxyToken.isNullOrBlank()) {
        log.warn("SOUZ_BACKEND_PROXY_TOKEN is not configured; /v1 routes will reject all requests.")
    }

    val runtime = BackendRuntime.create(appConfig)
    val server = BackendHttpServer(
        bootstrapService = runtime.bootstrapService,
        userSettingsService = runtime.userSettingsService,
        providerKeyService = runtime.userProviderKeyService,
        chatService = runtime.chatService,
        messageService = runtime.messageService,
        executionService = runtime.executionService,
        optionService = runtime.optionService,
        eventService = runtime.eventService,
        featureFlags = runtime.featureFlags,
        selectedModel = runtime::selectedModel,
        bindAddress = InetSocketAddress(host, port),
        trustedProxyToken = { appConfig.proxyToken },
        ensureTrustedUser = runtime.userRepository::ensureUser,
    )
    val shutdown = Runnable {
        runCatching { server.close() }
            .onFailure { log.warn("Failed to stop backend server: {}", it.message) }
        runCatching { runtime.close() }
            .onFailure { log.warn("Failed to close backend runtime: {}", it.message) }
    }
    Runtime.getRuntime().addShutdownHook(Thread(shutdown, "souz-backend-shutdown"))

    server.start()
    log.info("Bootstrap API: GET http://{}:{}/v1/bootstrap", host, port)
    CountDownLatch(1).await()
}

private fun configValue(envKey: String, propertyKey: String): String? =
    System.getenv(envKey)
        ?: System.getProperty(propertyKey)
