package ru.souz.backend.http

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.net.InetSocketAddress
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.backend.agent.model.AgentRequest
import ru.souz.backend.agent.service.BackendAgentService
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.common.BackendRequestException
import ru.souz.backend.security.RequestIdentityPlugin
import ru.souz.backend.security.requestIdentity

/** Health-check response returned by `GET /health`. */
data class HealthResponse(
    val status: String,
    val model: String,
)

/** Root endpoint response describing the available backend routes. */
data class RootResponse(
    val service: String,
    val endpoints: List<String>,
)

/** Minimal JSON error envelope used by backend routes. */
data class ErrorResponse(
    val error: String,
)

/** Embedded Ktor server wrapper for the Souz backend HTTP API. */
class BackendHttpServer(
    private val agentService: BackendAgentService,
    private val bootstrapService: BackendBootstrapService,
    private val selectedModel: () -> String,
    private val bindAddress: InetSocketAddress,
    private val internalAgentToken: () -> String? = { null },
    private val trustedProxyToken: () -> String? = { null },
) : AutoCloseable {
    private val l = LoggerFactory.getLogger(BackendHttpServer::class.java)
    private val server = embeddedServer(
        factory = Netty,
        host = bindAddress.hostString,
        port = bindAddress.port,
    ) {
        backendApplication(
            agentService = agentService,
            bootstrapService = bootstrapService,
            selectedModel = selectedModel,
            internalAgentToken = internalAgentToken,
            trustedProxyToken = trustedProxyToken,
        )
    }
    private var startedAddress: InetSocketAddress? = null

    val address: InetSocketAddress
        get() = startedAddress ?: bindAddress

    fun start() {
        server.start(wait = false)
        startedAddress = bindAddress
        l.info("Souz backend started on http://{}:{}", address.hostString, address.port)
    }

    override fun close() {
        server.stop(gracePeriodMillis = STOP_GRACE_PERIOD_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    private companion object {
        const val STOP_GRACE_PERIOD_MILLIS = 500L
        const val STOP_TIMEOUT_MILLIS = 1_000L
    }
}

/** Installs backend HTTP routes into a Ktor application. */
fun Application.backendApplication(
    agentService: BackendAgentService,
    bootstrapService: BackendBootstrapService,
    selectedModel: () -> String,
    internalAgentToken: () -> String? = { null },
    trustedProxyToken: () -> String? = { null },
) {
    val l = LoggerFactory.getLogger("SouzBackendRoutes")

    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
    }
    install(StatusPages) {
        exception<BackendV1Exception> { call, cause ->
            call.respond(
                cause.status,
                BackendV1ErrorEnvelope(
                    error = BackendV1Error(code = cause.code, message = cause.message),
                ),
            )
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) {
                throw cause
            }
            l.error("Unhandled backend request failure", cause)
            if (call.request.path().startsWith("/v1/")) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    BackendV1ErrorEnvelope(
                        error = BackendV1Error(
                            code = "internal_error",
                            message = "Internal server error.",
                        ),
                    ),
                )
            } else {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error."))
            }
        }
    }
    install(RequestIdentityPlugin) {
        this.trustedProxyToken = trustedProxyToken
    }

    routing {
        get("/") {
            call.respondBackend(l) {
                RootResponse(
                    service = "souz-backend",
                    endpoints = listOf(
                        "GET /health",
                        "GET /v1/bootstrap",
                        "POST /agent",
                    ),
                )
            }
        }

        get("/health") {
            call.respondBackend(l) {
                HealthResponse(status = "ok", model = selectedModel())
            }
        }

        route("/v1") {
            get("/bootstrap") {
                call.respond(
                    bootstrapService.response(call.requestIdentity())
                )
            }
        }

        post("/agent") {
            call.respondBackend(l) {
                requireAgentAuthorization(internalAgentToken())
                requireJsonContent()
                val request = receiveOrBadRequest<AgentRequest>()
                requireMatchingRequestId(request.requestId)
                agentService.sendAgentRequest(request)
            }
        }
    }
}

private suspend fun ApplicationCall.respondBackend(
    logger: org.slf4j.Logger,
    block: suspend ApplicationCall.() -> Any,
) {
    try {
        respond(block())
    } catch (e: BackendRequestException) {
        respond(HttpStatusCode.fromValue(e.statusCode), ErrorResponse(e.message))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Unhandled backend request failure", e)
        respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error."))
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrBadRequest(): T =
    try {
        receive<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BackendRequestException(400, "Invalid payload: ${e.message ?: "request body cannot be parsed."}")
    }

private fun ApplicationCall.requireAgentAuthorization(expectedToken: String?) {
    val token = expectedToken?.trim().takeUnless { it.isNullOrEmpty() }
        ?: throw BackendRequestException(401, "Missing or invalid internal token.")
    val authorization = request.header(HttpHeaders.Authorization)?.trim().orEmpty()
    val actualToken = authorization.removePrefix(BEARER_PREFIX).takeIf {
        authorization.startsWith(BEARER_PREFIX)
    }?.trim()
    if (actualToken != token) {
        throw BackendRequestException(401, "Missing or invalid internal token.")
    }
}

private fun ApplicationCall.requireJsonContent() {
    val contentType = request.contentType()
    if (
        contentType.contentType != ContentType.Application.Json.contentType ||
        contentType.contentSubtype != ContentType.Application.Json.contentSubtype
    ) {
        throw BackendRequestException(400, "Content-Type must be application/json.")
    }
}

private fun ApplicationCall.requireMatchingRequestId(bodyRequestId: String) {
    val headerRequestId = request.header(REQUEST_ID_HEADER)?.trim()
    val headerUuid = headerRequestId?.toUuidOrNull()
    val bodyUuid = bodyRequestId.toUuidOrNull()
    if (headerUuid == null) {
        throw BackendRequestException(400, "$REQUEST_ID_HEADER must be a UUID.")
    }
    if (bodyUuid == null || headerUuid != bodyUuid) {
        throw BackendRequestException(400, "$REQUEST_ID_HEADER must match requestId.")
    }
}

private fun String.toUuidOrNull(): UUID? =
    runCatching { UUID.fromString(this) }.getOrNull()

private const val BEARER_PREFIX = "Bearer "
private const val REQUEST_ID_HEADER = "X-Request-Id"
