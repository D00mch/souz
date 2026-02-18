package ru.souz.server

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
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

fun startLocalServer(
    agentNode: AgentNode,
    config: LocalServerConfig = LocalServerConfig()
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {

    return embeddedServer(Netty, port = config.port, host = config.host) {
        configureServer(agentNode)
    }.start(wait = false)
}

fun Application.configureServer(agentNode: AgentNode) {
    install(ContentNegotiation) {
        jackson {
            setSerializationInclusion(JsonInclude.Include.NON_NULL)
            this.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Error processing request", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                AgentResponse.Error(error = cause.message ?: "Unknown error")
            )
        }
    }

    routing {
        route("/api/agent") {
            post("/command") {
                val request = call.receive<AgentRequest>()

                val result = agentNode.processRequest(request.text)

                call.respond(HttpStatusCode.OK, AgentResponse.Success(
                    result = result.response,
                    history = result.history
                ))
            }
        }

        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}