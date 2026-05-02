package ru.souz.backend.http

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.net.InetSocketAddress
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.backend.agent.service.BackendAgentService
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.chat.service.ChatService
import ru.souz.backend.chat.service.MessageService
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.service.AgentExecutionService
import ru.souz.backend.http.routes.legacyAgentRoutes
import ru.souz.backend.http.routes.v1Routes
import ru.souz.backend.keys.service.UserProviderKeyService
import ru.souz.backend.options.service.OptionService
import ru.souz.backend.security.RequestIdentityPlugin
import ru.souz.backend.settings.service.UserSettingsService

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
class BackendHttpServer(
    agentService: BackendAgentService,
    bootstrapService: BackendBootstrapService,
    userSettingsService: UserSettingsService? = null,
    providerKeyService: UserProviderKeyService? = null,
    chatService: ChatService? = null,
    messageService: MessageService? = null,
    executionService: AgentExecutionService? = null,
    optionService: OptionService? = null,
    eventService: AgentEventService? = null,
    featureFlags: BackendFeatureFlags = BackendFeatureFlags(),
    selectedModel: () -> String,
    private val bindAddress: InetSocketAddress,
    internalAgentToken: () -> String? = { null },
    trustedProxyToken: () -> String? = { null },
    ensureTrustedUser: suspend (String) -> Unit = { _ -> },
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(BackendHttpServer::class.java)
    private val dependencies = BackendHttpDependencies(
        agentService = agentService,
        bootstrapService = bootstrapService,
        userSettingsService = userSettingsService,
        providerKeyService = providerKeyService,
        chatService = chatService,
        messageService = messageService,
        executionService = executionService,
        optionService = optionService,
        eventService = eventService,
        featureFlags = featureFlags,
        selectedModel = selectedModel,
        internalAgentToken = internalAgentToken,
        trustedProxyToken = trustedProxyToken,
        ensureTrustedUser = ensureTrustedUser,
    )
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
fun Application.backendApplication(
    agentService: BackendAgentService,
    bootstrapService: BackendBootstrapService,
    userSettingsService: UserSettingsService? = null,
    providerKeyService: UserProviderKeyService? = null,
    chatService: ChatService? = null,
    messageService: MessageService? = null,
    executionService: AgentExecutionService? = null,
    optionService: OptionService? = null,
    eventService: AgentEventService? = null,
    featureFlags: BackendFeatureFlags = BackendFeatureFlags(),
    selectedModel: () -> String,
    internalAgentToken: () -> String? = { null },
    trustedProxyToken: () -> String? = { null },
    ensureTrustedUser: suspend (String) -> Unit = { _ -> },
) {
    configureBackendHttpServer(
        BackendHttpDependencies(
            agentService = agentService,
            bootstrapService = bootstrapService,
            userSettingsService = userSettingsService,
            providerKeyService = providerKeyService,
            chatService = chatService,
            messageService = messageService,
            executionService = executionService,
            optionService = optionService,
            eventService = eventService,
            featureFlags = featureFlags,
            selectedModel = selectedModel,
            internalAgentToken = internalAgentToken,
            trustedProxyToken = trustedProxyToken,
            ensureTrustedUser = ensureTrustedUser,
        )
    )
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

    routing {
        get(BackendHttpRoutes.ROOT) {
            call.respondBackend(logger) {
                RootResponse(
                    service = "souz-backend",
                    endpoints = ROOT_ENDPOINTS,
                )
            }
        }

        get(BackendHttpRoutes.HEALTH) {
            call.respondBackend(logger) {
                HealthResponse(status = "ok", model = dependencies.selectedModel())
            }
        }

        v1Routes(dependencies)
        legacyAgentRoutes(dependencies)
    }
}

private val ROOT_ENDPOINTS = listOf(
    "GET ${BackendHttpRoutes.HEALTH}",
    "GET ${BackendHttpRoutes.BOOTSTRAP}",
    "GET ${BackendHttpRoutes.SETTINGS}",
    "PATCH ${BackendHttpRoutes.SETTINGS}",
    "GET ${BackendHttpRoutes.PROVIDER_KEYS}",
    "PUT ${BackendHttpRoutes.PROVIDER_KEY_PATTERN}",
    "DELETE ${BackendHttpRoutes.PROVIDER_KEY_PATTERN}",
    "GET ${BackendHttpRoutes.CHATS}",
    "POST ${BackendHttpRoutes.CHATS}",
    "PATCH ${BackendHttpRoutes.CHAT_TITLE_PATTERN}",
    "POST ${BackendHttpRoutes.CHAT_ARCHIVE_PATTERN}",
    "POST ${BackendHttpRoutes.CHAT_UNARCHIVE_PATTERN}",
    "GET ${BackendHttpRoutes.CHAT_MESSAGES_PATTERN}",
    "GET ${BackendHttpRoutes.CHAT_EVENTS_PATTERN}",
    "POST ${BackendHttpRoutes.CHAT_MESSAGES_PATTERN}",
    "POST ${BackendHttpRoutes.CHAT_CANCEL_ACTIVE_PATTERN}",
    "POST ${BackendHttpRoutes.CHAT_EXECUTION_CANCEL_PATTERN}",
    "POST ${BackendHttpRoutes.OPTION_ANSWER_PATTERN}",
    "WS ${BackendHttpRoutes.CHAT_WS_PATTERN}",
    "POST ${BackendHttpRoutes.LEGACY_AGENT}",
)
