package ru.gigadesk.server

import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("LocalServer")

/**
 * Configuration for the local server.
 */
data class LocalServerConfig(
    val port: Int = 8080,
    val host: String = "0.0.0.0"
)

/**
 * Starts the local Ktor server that accepts commands from the mobile companion app.
 *
 * @param agentNode The agent node to process incoming requests.
 * @param config Server configuration (port, host).
 * @return The embedded server instance (can be used to stop the server).
 */
fun startLocalServer(
    agentNode: AgentNode,
    config: LocalServerConfig = LocalServerConfig()
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    logger.info("Starting local server on ${config.host}:${config.port}")
    
    return embeddedServer(Netty, port = config.port, host = config.host) {
        configureServer(agentNode)
    }.start(wait = false)
}

/**
 * Configures the Ktor application with all necessary plugins and routes.
 * Exposed for testing purposes.
 */
fun Application.configureServer(agentNode: AgentNode) {
    install(ContentNegotiation) {
        jackson()
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Error processing request", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                AgentErrorResponse(cause.message ?: "Unknown error")
            )
        }
    }

    routing {
        route("/api/agent") {
            post("/command") {
                val clientHost = call.request.origin.remoteHost
                val contentLength = call.request.headers["Content-Length"]
                logger.info("📱 Incoming request from $clientHost (Content-Length: $contentLength)")
                
                val request = call.receive<AgentRequest>()
                val textPreview = request.text.take(100).let { 
                    if (request.text.length > 100) "$it..." else it 
                }
                logger.info("📨 Received command from mobile app: \"$textPreview\"")
                logger.debug("Full command text: ${request.text}")
                
                val startTime = System.currentTimeMillis()
                val result = agentNode.processRequest(request.text)
                val processingTime = System.currentTimeMillis() - startTime
                
                val resultPreview = result.take(100).let {
                    if (result.length > 100) "$it..." else it
                }
                logger.info("✅ Command processed in ${processingTime}ms, response: \"$resultPreview\"")
                logger.debug("Full response: $result")
                
                call.respond(HttpStatusCode.OK, AgentResponse(result))
                logger.info("📤 Response sent to $clientHost")
            }
        }
        
        // Health check endpoint
        get("/health") {
            val clientHost = call.request.origin.remoteHost
            logger.debug("💓 Health check from $clientHost")
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
