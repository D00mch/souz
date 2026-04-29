package ru.souz.backend

import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("SouzBackend")

fun main() {
    val host = configValue(envKey = "SOUZ_BACKEND_HOST", propertyKey = "souz.backend.host")
        ?: "127.0.0.1"
    val port = configValue(envKey = "SOUZ_BACKEND_PORT", propertyKey = "souz.backend.port")
        ?.toIntOrNull()
        ?: 8080
    val internalAgentToken = configValue(
        envKey = "SOUZ_BACKEND_AGENT_TOKEN",
        propertyKey = "souz.backend.agentToken",
    )
    if (internalAgentToken.isNullOrBlank()) {
        log.warn("SOUZ_BACKEND_AGENT_TOKEN is not configured; POST /agent will reject all requests.")
    }

    val runtime = BackendRuntime.create()
    val server = BackendHttpServer(
        chatService = runtime.chatService,
        selectedModel = runtime::selectedModel,
        bindAddress = InetSocketAddress(host, port),
        internalAgentToken = { internalAgentToken },
    )
    val shutdown = Runnable {
        runCatching { server.close() }
            .onFailure { log.warn("Failed to stop backend server: {}", it.message) }
        runCatching { runtime.close() }
            .onFailure { log.warn("Failed to close backend runtime: {}", it.message) }
    }
    Runtime.getRuntime().addShutdownHook(Thread(shutdown, "souz-backend-shutdown"))

    server.start()
    log.info("REST API: POST http://{}:{}/chat with JSON {\"message\":\"...\"}", host, port)
    log.info("Internal agent API: POST http://{}:{}/agent", host, port)
    CountDownLatch(1).await()
}

private fun configValue(envKey: String, propertyKey: String): String? =
    System.getenv(envKey)
        ?: System.getProperty(propertyKey)
