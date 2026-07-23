package ru.souz.backend.http

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Components
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.SecuritySchemeIn
import io.ktor.openapi.reflect.ReflectionJsonSchemaInference
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import io.ktor.server.routing.openapi.OpenApiDocSource
import io.ktor.server.routing.openapi.registerApiKeySecurityScheme
import io.ktor.server.websocket.WebSockets
import java.net.InetSocketAddress
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.routes.v1Routes
import ru.souz.backend.security.RequestIdentityPlugin

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

/** Embedded Ktor server wrapper for the Souz backend HTTP API. */
internal class BackendHttpServer(
    private val dependencies: BackendHttpDependencies,
    private val bindAddress: InetSocketAddress,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(BackendHttpServer::class.java)
    private val server = embeddedServer(
        factory = Netty,
        host = bindAddress.hostString,
        port = bindAddress.port,
    ) {
        configureBackendHttpServer(dependencies)
    }
    private var startedAddress: InetSocketAddress? = null

    val address: InetSocketAddress
        get() = startedAddress ?: bindAddress

    fun start() {
        server.start(wait = false)
        startedAddress = bindAddress
        logger.info("Souz backend started on http://{}:{}", address.hostString, address.port)
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
internal fun Application.backendApplication(
    dependencies: BackendHttpDependencies,
) {
    configureBackendHttpServer(dependencies)
}

internal fun Application.configureBackendHttpServer(dependencies: BackendHttpDependencies) {
    val logger = LoggerFactory.getLogger("SouzBackendRoutes")

    install(ContentNegotiation) {
        jackson {
            registerKotlinModule()
            disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        }
    }
    install(WebSockets)
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
            logger.error("Unhandled backend request failure", cause)
            if (BackendHttpRoutes.isV1Path(call.request.path())) {
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
        trustedProxyToken = dependencies.trustedProxyToken
        ensureUser = dependencies.ensureTrustedUser
    }
    registerApiKeySecurityScheme(
        name = BackendOpenApiSecurity.PROXY_AUTH_SCHEME,
        keyName = BackendOpenApiSecurity.PROXY_AUTH_HEADER,
        keyLocation = SecuritySchemeIn.HEADER,
        description = "Proxy-injected shared authentication credential. Clients must not invent or persist this value.",
    )
    registerApiKeySecurityScheme(
        name = BackendOpenApiSecurity.USER_IDENTITY_SCHEME,
        keyName = BackendOpenApiSecurity.USER_IDENTITY_HEADER,
        keyLocation = SecuritySchemeIn.HEADER,
        description = "Proxy-injected trusted user identity. It is an opaque identity value, not a client-selected user ID.",
    )

    routing {
        get(BackendHttpRoutes.ROOT) {
            call.respondBackend(logger) {
                RootResponse(
                    service = "souz-backend",
                    endpoints = rootEndpoints(dependencies.featureFlags),
                )
            }
        }.describePublic(
            operationId = "getRoot",
            tag = BackendOpenApiTags.SYSTEM,
            summary = "Get backend route index",
            description = "Returns the backend service name and its currently advertised endpoints.",
        ) {
            responses {
                jsonResponse<RootResponse>(HttpStatusCode.OK, "Backend service and endpoint index.")
                legacyErrorResponse()
            }
        }

        get(BackendHttpRoutes.HEALTH) {
            call.respondBackend(logger) {
                HealthResponse(status = "ok", model = dependencies.selectedModel())
            }
        }.describePublic(
            operationId = "getHealth",
            tag = BackendOpenApiTags.SYSTEM,
            summary = "Get backend health",
            description = "Returns process health and the currently selected model.",
        ) {
            responses {
                jsonResponse<HealthResponse>(HttpStatusCode.OK, "The backend is healthy.")
                legacyErrorResponse()
            }
        }

        v1Routes(dependencies)

        swaggerUI(BackendHttpRoutes.DOCS) {
            openapiVersion = "3.1.1"
            info = OpenApiInfo(
                title = "Souz Backend API",
                version = "1.0.0",
            )
            components = Components(schemas = BackendEventOpenApiSchemas.components)
            remotePath = "openapi.json"
            source = OpenApiDocSource.Routing(
                contentType = ContentType.Application.Json,
                schemaInference = ReflectionJsonSchemaInference.Default,
                routes = { routingRoot.descendants() },
            )
        }
    }
}

private fun rootEndpoints(featureFlags: BackendFeatureFlags): List<String> =
    buildList {
        add("GET ${BackendHttpRoutes.HEALTH}")
        add("GET ${BackendHttpRoutes.DOCS}")
        add("GET ${BackendHttpRoutes.BOOTSTRAP}")
        add("GET ${BackendHttpRoutes.ONBOARDING_STATE}")
        add("POST ${BackendHttpRoutes.ONBOARDING_COMPLETE}")
        add("GET ${BackendHttpRoutes.SETTINGS}")
        add("PATCH ${BackendHttpRoutes.SETTINGS}")
        add("GET ${BackendHttpRoutes.PROVIDER_KEYS}")
        add("PUT ${BackendHttpRoutes.PROVIDER_KEY_PATTERN}")
        add("DELETE ${BackendHttpRoutes.PROVIDER_KEY_PATTERN}")
        add("GET ${BackendHttpRoutes.CHATS}")
        add("POST ${BackendHttpRoutes.CHATS}")
        add("PATCH ${BackendHttpRoutes.CHAT_TITLE_PATTERN}")
        add("POST ${BackendHttpRoutes.CHAT_ARCHIVE_PATTERN}")
        add("POST ${BackendHttpRoutes.CHAT_UNARCHIVE_PATTERN}")
        add("GET ${BackendHttpRoutes.CHAT_MESSAGES_PATTERN}")
        add("GET ${BackendHttpRoutes.CHAT_EVENTS_PATTERN}")
        add("POST ${BackendHttpRoutes.CHAT_MESSAGES_PATTERN}")
        if (featureFlags.telegramBot) {
            add("GET ${BackendHttpRoutes.CHAT_TELEGRAM_BOT_PATTERN}")
            add("PUT ${BackendHttpRoutes.CHAT_TELEGRAM_BOT_PATTERN}")
            add("DELETE ${BackendHttpRoutes.CHAT_TELEGRAM_BOT_PATTERN}")
        }
        add("POST ${BackendHttpRoutes.CHAT_CANCEL_ACTIVE_PATTERN}")
        add("POST ${BackendHttpRoutes.CHAT_EXECUTION_CANCEL_PATTERN}")
        add("POST ${BackendHttpRoutes.OPTION_ANSWER_PATTERN}")
        add("GET ${BackendHttpRoutes.CHAT_PENDING_PERMISSION_REQUESTS_PATTERN}")
        add("PUT ${BackendHttpRoutes.PERMISSION_DECISION_PATTERN}")
        add("WS ${BackendHttpRoutes.CHAT_WS_PATTERN}")
    }
